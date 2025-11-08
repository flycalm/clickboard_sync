package com.clipboardsync

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

@Serializable
data class DiscoveryMessage(
    val deviceType: String,
    val deviceName: String,
    val ipAddress: String,
    val port: Int,
    val timestamp: Long
)

data class DiscoveredDevice(
    val deviceType: String,
    val deviceName: String,
    val ipAddress: String,
    val port: Int,
    val lastSeen: Long
)

class DeviceDiscovery(private val context: Context) {
    private var socket: DatagramSocket? = null
    private var listenJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    private val _discoveredDevicesFlow = MutableStateFlow<List<DiscoveredDevice>>(emptyList())
    val discoveredDevicesFlow = _discoveredDevicesFlow.asStateFlow()
    
    private val discoveredDevices = mutableMapOf<String, DiscoveredDevice>()
    
    private val json = Json { 
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val DISCOVERY_PORT = 5149

    fun start() {
        try {
            // 启用多播锁
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val multicastLock = wifiManager.createMulticastLock("ClipboardSync")
            multicastLock.acquire()

            socket = DatagramSocket(DISCOVERY_PORT)
            socket?.broadcast = true

            Log.d("DeviceDiscovery", "开始监听发现消息...")

            listenJob = scope.launch {
                listenForDiscovery()
            }

            // 定期清理过期设备
            scope.launch {
                while (isActive) {
                    delay(10000) // 每10秒
                    cleanupExpiredDevices()
                }
            }
        } catch (e: Exception) {
            Log.e("DeviceDiscovery", "启动失败", e)
        }
    }

    private suspend fun listenForDiscovery() {
        val buffer = ByteArray(1024)
        
        while (socket != null && !socket!!.isClosed) {
            try {
                val packet = DatagramPacket(buffer, buffer.size)
                socket?.receive(packet)
                
                val message = String(packet.data, 0, packet.length)
                
                try {
                    val discoveryMessage = json.decodeFromString<DiscoveryMessage>(message)
                    
                    // 只处理 Windows 设备
                    if (discoveryMessage.deviceType == "windows") {
                        val device = DiscoveredDevice(
                            deviceType = discoveryMessage.deviceType,
                            deviceName = discoveryMessage.deviceName,
                            ipAddress = discoveryMessage.ipAddress,
                            port = discoveryMessage.port,
                            lastSeen = System.currentTimeMillis()
                        )
                        
                        discoveredDevices[discoveryMessage.ipAddress] = device
                        updateDeviceList()
                        
                        Log.d("DeviceDiscovery", "发现设备: ${device.deviceName} (${device.ipAddress}:${device.port})")
                    }
                } catch (e: Exception) {
                    // 忽略解析错误
                }
            } catch (e: Exception) {
                if (socket?.isClosed == false) {
                    Log.e("DeviceDiscovery", "接收失败", e)
                }
            }
        }
    }

    private fun cleanupExpiredDevices() {
        val now = System.currentTimeMillis()
        val expiredKeys = discoveredDevices.filter { (_, device) ->
            now - device.lastSeen > 30000 // 30秒未更新则移除
        }.keys
        
        if (expiredKeys.isNotEmpty()) {
            expiredKeys.forEach { discoveredDevices.remove(it) }
            scope.launch { updateDeviceList() }
        }
    }

    private suspend fun updateDeviceList() {
        _discoveredDevicesFlow.emit(discoveredDevices.values.toList())
    }

    fun stop() {
        listenJob?.cancel()
        socket?.close()
        socket = null
        discoveredDevices.clear()
        scope.launch { updateDeviceList() }
        Log.d("DeviceDiscovery", "已停止")
    }
}
