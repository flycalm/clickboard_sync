package com.clipboardsync

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString

@Serializable
data class ConnectionHistory(
    val ip: String,
    val port: Int,
    val deviceName: String = "",
    val lastConnected: Long = System.currentTimeMillis()
)

class MainActivity : ComponentActivity() {
    private lateinit var clipboardReceiver: ClipboardReceiver
    private lateinit var deviceDiscovery: DeviceDiscovery

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        clipboardReceiver = ClipboardReceiver(this)
        deviceDiscovery = DeviceDiscovery(this)

        setContent {
            MaterialTheme {
                ClipboardSyncScreen(
                    context = this,
                    clipboardReceiver = clipboardReceiver,
                    deviceDiscovery = deviceDiscovery
                )
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        clipboardReceiver.stop()
        deviceDiscovery.stop()
    }
}

// 历史记录管理
object HistoryManager {
    private const val PREFS_NAME = "clipboard_sync_history"
    private const val KEY_HISTORY = "connection_history"
    private val json = Json { ignoreUnknownKeys = true }
    
    fun saveHistory(context: Context, history: List<ConnectionHistory>) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val jsonString = json.encodeToString(history)
        prefs.edit().putString(KEY_HISTORY, jsonString).apply()
    }
    
    fun loadHistory(context: Context): List<ConnectionHistory> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val jsonString = prefs.getString(KEY_HISTORY, null) ?: return emptyList()
        return try {
            json.decodeFromString(jsonString)
        } catch (e: Exception) {
            emptyList()
        }
    }
    
    fun addToHistory(context: Context, ip: String, port: Int, deviceName: String = "") {
        val history = loadHistory(context).toMutableList()
        // 移除重复项
        history.removeAll { it.ip == ip && it.port == port }
        // 添加到开头
        history.add(0, ConnectionHistory(ip, port, deviceName, System.currentTimeMillis()))
        // 只保留最近10条
        if (history.size > 10) {
            history.subList(10, history.size).clear()
        }
        saveHistory(context, history)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClipboardSyncScreen(
    context: Context,
    clipboardReceiver: ClipboardReceiver,
    deviceDiscovery: DeviceDiscovery
) {
    var isRunning by remember { mutableStateOf(false) }
    var statusText by remember { mutableStateOf("状态: 未启动") }
    var connectedDevice by remember { mutableStateOf("未连接") }
    var currentConnectedIp by remember { mutableStateOf("") }
    var logMessages by remember { mutableStateOf(listOf<String>()) }
    var discoveredDevices by remember { mutableStateOf(listOf<DiscoveredDevice>()) }
    var showManualConnect by remember { mutableStateOf(false) }
    var manualIp by remember { mutableStateOf("") }
    var manualPort by remember { mutableStateOf("5150") }
    var connectionHistory by remember { mutableStateOf(HistoryManager.loadHistory(context)) }
    var autoSyncEnabled by remember { mutableStateOf(false) }

    val scope = rememberCoroutineScope()
    
    // 监听连接状态
    LaunchedEffect(Unit) {
        clipboardReceiver.connectionStateFlow.collect { isConnected ->
            if (!isConnected) {
                currentConnectedIp = ""
            }
        }
    }

    // 监听日志更新
    LaunchedEffect(Unit) {
        clipboardReceiver.logFlow.collect { message ->
            logMessages = (listOf(message) + logMessages).take(50)
        }
    }

    // 监听发现的设备
    LaunchedEffect(Unit) {
        deviceDiscovery.discoveredDevicesFlow.collect { devices ->
            discoveredDevices = devices
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("剪贴板同步工具") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
        ) {
            // 状态信息
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = statusText,
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "已连接: $connectedDevice",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    
                    // 自动同步开关
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "自动同步文本",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Switch(
                            checked = autoSyncEnabled,
                            onCheckedChange = { enabled ->
                                autoSyncEnabled = enabled
                                clipboardReceiver.setAutoSync(enabled)
                                if (enabled) {
                                    scope.launch {
                                        clipboardReceiver.logFlow.collect { message ->
                                            logMessages = (listOf(message) + logMessages).take(50)
                                        }
                                    }
                                }
                            },
                            enabled = isRunning
                        )
                    }
                    if (autoSyncEnabled) {
                        Text(
                            text = "将自动发送手机剪贴板文本到电脑",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // 手动连接区域
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "手动连接",
                        style = MaterialTheme.typography.titleSmall
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    if (showManualConnect) {
                        OutlinedTextField(
                            value = manualIp,
                            onValueChange = { manualIp = it },
                            label = { Text("IP 地址") },
                            placeholder = { Text("例如: 192.168.1.100") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        
                        OutlinedTextField(
                            value = manualPort,
                            onValueChange = { manualPort = it },
                            label = { Text("端口") },
                            placeholder = { Text("默认: 5150") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = {
                                    scope.launch {
                                        val port = manualPort.toIntOrNull() ?: 5150
                                        clipboardReceiver.connectToServer(manualIp, port)
                                        connectedDevice = "$manualIp:$port"
                                        currentConnectedIp = "$manualIp:$port"
                                        isRunning = true
                                        statusText = "状态: 已连接"
                                        
                                        // 添加到历史记录
                                        HistoryManager.addToHistory(context, manualIp, port)
                                        connectionHistory = HistoryManager.loadHistory(context)
                                        
                                        showManualConnect = false
                                    }
                                },
                                enabled = manualIp.isNotEmpty(),
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("连接")
                            }
                            
                            OutlinedButton(
                                onClick = { showManualConnect = false },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("取消")
                            }
                        }
                    } else {
                        Button(
                            onClick = { showManualConnect = true },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("手动输入 IP 地址")
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // 连接历史记录
            if (connectionHistory.isNotEmpty()) {
                Text(
                    text = "连接历史:",
                    style = MaterialTheme.typography.titleSmall
                )
                Spacer(modifier = Modifier.height(8.dp))
                connectionHistory.forEach { history ->
                    val isConnected = currentConnectedIp == "${history.ip}:${history.port}"
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        onClick = {
                            scope.launch {
                                clipboardReceiver.connectToServer(history.ip, history.port)
                                connectedDevice = "${history.ip}:${history.port}"
                                currentConnectedIp = "${history.ip}:${history.port}"
                                isRunning = true
                                statusText = "状态: 已连接"
                                
                                // 更新历史记录
                                HistoryManager.addToHistory(context, history.ip, history.port, history.deviceName)
                                connectionHistory = HistoryManager.loadHistory(context)
                            }
                        },
                        colors = if (isConnected) {
                            CardDefaults.cardColors(
                                containerColor = Color(0xFF4CAF50).copy(alpha = 0.2f)
                            )
                        } else {
                            CardDefaults.cardColors()
                        }
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                if (history.deviceName.isNotEmpty()) {
                                    Text(text = history.deviceName, style = MaterialTheme.typography.titleSmall)
                                }
                                Text(
                                    text = "${history.ip}:${history.port}",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                            if (isConnected) {
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = "已连接",
                                    tint = Color(0xFF4CAF50)
                                )
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
            }

            // 发现的设备列表
            if (discoveredDevices.isNotEmpty()) {
                Text(
                    text = "发现的设备:",
                    style = MaterialTheme.typography.titleSmall
                )
                Spacer(modifier = Modifier.height(8.dp))
                discoveredDevices.forEach { device ->
                    val deviceKey = "${device.ipAddress}:${device.port}"
                    val isConnected = currentConnectedIp == deviceKey
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        onClick = {
                            scope.launch {
                                clipboardReceiver.connectToServer(device.ipAddress, device.port)
                                connectedDevice = "${device.deviceName} (${device.ipAddress}:${device.port})"
                                currentConnectedIp = deviceKey
                                isRunning = true
                                statusText = "状态: 已连接"
                                
                                // 添加到历史记录
                                HistoryManager.addToHistory(context, device.ipAddress, device.port, device.deviceName)
                                connectionHistory = HistoryManager.loadHistory(context)
                            }
                        },
                        colors = if (isConnected) {
                            CardDefaults.cardColors(
                                containerColor = Color(0xFF4CAF50).copy(alpha = 0.2f)
                            )
                        } else {
                            CardDefaults.cardColors()
                        }
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(text = device.deviceName, style = MaterialTheme.typography.titleSmall)
                                Text(text = "${device.ipAddress}:${device.port}", style = MaterialTheme.typography.bodySmall)
                            }
                            if (isConnected) {
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = "已连接",
                                    tint = Color(0xFF4CAF50)
                                )
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
            }

            // 控制按钮
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = {
                        scope.launch {
                            deviceDiscovery.start()
                            isRunning = true
                            statusText = "状态: 搜索设备中..."
                        }
                    },
                    enabled = !isRunning,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("开始搜索")
                }

                Button(
                    onClick = {
                        clipboardReceiver.stop()
                        deviceDiscovery.stop()
                        isRunning = false
                        statusText = "状态: 已停止"
                        connectedDevice = "未连接"
                        currentConnectedIp = ""
                    },
                    enabled = isRunning,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("停止")
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // 日志显示
            Text(
                text = "日志:",
                style = MaterialTheme.typography.titleSmall
            )
            Spacer(modifier = Modifier.height(8.dp))
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(8.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    logMessages.forEach { log ->
                        Text(
                            text = log,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(vertical = 2.dp)
                        )
                    }
                }
            }
        }
    }
}
