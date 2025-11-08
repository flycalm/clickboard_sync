package com.clipboardsync

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import android.util.Log
import androidx.core.content.FileProvider
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.net.Socket
import java.text.SimpleDateFormat
import java.util.*

@Serializable
data class ClipboardMessage(
    val type: String,
    val contentType: String,
    val content: String,
    val timestamp: Long
)

class ClipboardReceiver(private val context: Context) {
    private var socket: Socket? = null
    private var receiveJob: Job? = null
    private var clipboardMonitorJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    private val _logFlow = MutableSharedFlow<String>()
    val logFlow = _logFlow.asSharedFlow()
    
    private val _connectionStateFlow = MutableSharedFlow<Boolean>(replay = 1)
    val connectionStateFlow = _connectionStateFlow.asSharedFlow()
    
    private var lastClipboardText: String? = null
    private var autoSyncEnabled = false

    private val json = Json { 
        ignoreUnknownKeys = true
        isLenient = true
    }

    suspend fun connectToServer(host: String, port: Int) {
        withContext(Dispatchers.IO) {
            try {
                addLog("正在连接到 $host:$port...")
                
                // 关闭旧连接
                socket?.close()
                
                // 创建新连接,设置超时
                socket = Socket()
                socket?.connect(java.net.InetSocketAddress(host, port), 5000)
                socket?.soTimeout = 30000
                
                addLog("已成功连接到服务器")
                _connectionStateFlow.emit(true)
                
                // 启动接收任务
                receiveJob?.cancel()
                receiveJob = scope.launch {
                    receiveMessages()
                }
                
                // 启动剪贴板监听(如果启用)
                if (autoSyncEnabled) {
                    startClipboardMonitor()
                }
                Unit
            } catch (e: java.net.SocketTimeoutException) {
                addLog("连接超时: 请检查网络和防火墙设置")
                _connectionStateFlow.emit(false)
                Log.e("ClipboardReceiver", "Connection timeout", e)
            } catch (e: java.net.ConnectException) {
                addLog("连接被拒绝: ${e.message}")
                _connectionStateFlow.emit(false)
                Log.e("ClipboardReceiver", "Connection refused", e)
            } catch (e: Exception) {
                addLog("连接失败: ${e.javaClass.simpleName} - ${e.message}")
                _connectionStateFlow.emit(false)
                Log.e("ClipboardReceiver", "Connection failed", e)
            }
        }
    }
    
    fun setAutoSync(enabled: Boolean) {
        autoSyncEnabled = enabled
        if (enabled && socket?.isConnected == true) {
            startClipboardMonitor()
        } else {
            stopClipboardMonitor()
        }
    }
    
    private fun startClipboardMonitor() {
        clipboardMonitorJob?.cancel()
        clipboardMonitorJob = scope.launch {
            addLog("开始监听剪贴板")
            while (isActive && socket?.isConnected == true) {
                try {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    if (clipboard.hasPrimaryClip() && clipboard.primaryClipDescription?.hasMimeType("text/plain") == true) {
                        val text = clipboard.primaryClip?.getItemAt(0)?.text?.toString()
                        if (text != null && text != lastClipboardText && text.isNotEmpty()) {
                            lastClipboardText = text
                            sendTextToServer(text)
                        }
                    }
                } catch (e: Exception) {
                    Log.e("ClipboardReceiver", "Monitor clipboard error", e)
                }
                delay(1000)  // 每秒检查一次
            }
        }
    }
    
    private fun stopClipboardMonitor() {
        clipboardMonitorJob?.cancel()
        clipboardMonitorJob = null
    }
    
    private suspend fun sendTextToServer(text: String) {
        try {
            val message = ClipboardMessage(
                type = "clipboard",
                contentType = "text/plain",
                content = text,
                timestamp = System.currentTimeMillis()
            )
            
            val jsonString = json.encodeToString(ClipboardMessage.serializer(), message) + "\n"
            socket?.getOutputStream()?.write(jsonString.toByteArray(Charsets.UTF_8))
            socket?.getOutputStream()?.flush()
            
            val preview = if (text.length > 30) text.substring(0, 30) + "..." else text
            addLog("发送文本: $preview")
        } catch (e: Exception) {
            addLog("发送文本失败: ${e.message}")
            Log.e("ClipboardReceiver", "Send text error", e)
        }
    }

    private suspend fun receiveMessages() {
        try {
            val reader = BufferedReader(InputStreamReader(socket?.getInputStream()))
            var line: String?
            
            while (socket?.isConnected == true) {
                line = reader.readLine()
                if (line == null) break
                
                try {
                    val message = json.decodeFromString<ClipboardMessage>(line)
                    handleMessage(message)
                } catch (e: Exception) {
                    addLog("消息解析失败: ${e.message}")
                    Log.e("ClipboardReceiver", "Parse error", e)
                }
            }
        } catch (e: Exception) {
            addLog("接收消息失败: ${e.message}")
            Log.e("ClipboardReceiver", "Receive error", e)
        }
    }

    private suspend fun handleMessage(message: ClipboardMessage) {
        when (message.contentType) {
            "image/png" -> {
                addLog("收到图片消息 (${message.content.length / 1024} KB)")
                saveImageToClipboard(message.content)
            }
            "text/plain" -> {
                addLog("收到文本消息")
                saveTextToClipboard(message.content)
            }
            else -> {
                addLog("未知消息类型: ${message.contentType}")
            }
        }
    }

    private suspend fun saveImageToClipboard(base64Image: String) {
        withContext(Dispatchers.Main) {
            try {
                // 解码 Base64
                val imageBytes = Base64.decode(base64Image, Base64.DEFAULT)
                val bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
                
                // 保存到临时文件
                val imagesDir = File(context.cacheDir, "images")
                imagesDir.mkdirs()
                
                val imageFile = File(imagesDir, "clipboard_${System.currentTimeMillis()}.png")
                FileOutputStream(imageFile).use { out ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                }
                
                // 获取 Content URI
                val contentUri = FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    imageFile
                )
                
                // 写入剪贴板
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newUri(context.contentResolver, "Image", contentUri)
                clipboard.setPrimaryClip(clip)
                
                addLog("图片已保存到剪贴板")
            } catch (e: Exception) {
                addLog("保存图片失败: ${e.message}")
                Log.e("ClipboardReceiver", "Save image error", e)
            }
        }
    }

    private suspend fun saveTextToClipboard(text: String) {
        withContext(Dispatchers.Main) {
            try {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText("text", text)
                clipboard.setPrimaryClip(clip)
                
                addLog("文本已保存到剪贴板")
            } catch (e: Exception) {
                addLog("保存文本失败: ${e.message}")
                Log.e("ClipboardReceiver", "Save text error", e)
            }
        }
    }

    fun stop() {
        receiveJob?.cancel()
        clipboardMonitorJob?.cancel()
        socket?.close()
        socket = null
        scope.launch { 
            addLog("已断开连接")
            _connectionStateFlow.emit(false)
        }
    }

    private suspend fun addLog(message: String) {
        val timestamp = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        _logFlow.emit("[$timestamp] $message")
        Log.d("ClipboardReceiver", message)
    }
}
