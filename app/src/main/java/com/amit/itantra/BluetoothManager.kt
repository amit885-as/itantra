package com.amit.itantra

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.UUID

data class PairedDevice(
    val device: BluetoothDevice,
    val name: String,
    val address: String
)

@SuppressLint("MissingPermission")
class BluetoothManager(private val context: Context, private val adapter: BluetoothAdapter?) {
    companion object {
        // A standard UUID for our MVP connection
        val CUSTOM_UUID: UUID = UUID.fromString("8ce255c0-200a-11e0-ac64-0800200c9a66")
    }

    private val _connectionState = MutableStateFlow("Disconnected")
    val connectionState: StateFlow<String> = _connectionState

    private val _receivedMessage = MutableStateFlow<String?>(null)
    val receivedMessage: StateFlow<String?> = _receivedMessage

    private var activeSocket: BluetoothSocket? = null
    var lastConnectedDevice: BluetoothDevice? = null
        private set

    fun hasConnectPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.BLUETOOTH_CONNECT
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun closeSocket() {
        try {
            activeSocket?.close()
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            activeSocket = null
        }
    }

    // Sender role
    suspend fun connectToDevice(device: BluetoothDevice): Boolean = withContext(Dispatchers.IO) {
        if (!hasConnectPermission()) {
            _connectionState.value = "Bluetooth permission required"
            return@withContext false
        }
        closeSocket()
        _connectionState.value = "Connecting..."
        try {
            val socket = device.createRfcommSocketToServiceRecord(CUSTOM_UUID)
            socket.connect()
            activeSocket = socket
            lastConnectedDevice = device
            _connectionState.value = "Connected"

            // Listen for potential disconnects or messages
            listenForMessages()
            return@withContext true
        } catch (e: SecurityException) {
            e.printStackTrace()
            _connectionState.value = "Bluetooth permission required"
            return@withContext false
        } catch (e: Exception) {
            e.printStackTrace()
            closeSocket()
            _connectionState.value = "Disconnected"
            return@withContext false
        }
    }

    suspend fun reconnect(): Boolean {
        val device = lastConnectedDevice ?: return false
        return connectToDevice(device)
    }

    // Receiver role
    suspend fun startServer() = withContext(Dispatchers.IO) {
        if (!hasConnectPermission()) {
            _connectionState.value = "Bluetooth permission required"
            return@withContext
        }
        if (adapter == null || !adapter.isEnabled) {
            _connectionState.value = "Bluetooth disabled or unavailable"
            return@withContext
        }
        closeSocket()
        _connectionState.value = "Waiting for connection..."
        var serverSocket: BluetoothServerSocket? = null
        try {
            serverSocket = adapter.listenUsingRfcommWithServiceRecord("iTantra", CUSTOM_UUID)
            val socket = serverSocket?.accept()
            if (socket != null) {
                activeSocket = socket
                _connectionState.value = "Connected. Listening for messages..."
                serverSocket.close() // Close server since socket is established
                listenForMessages()
            }
        } catch (e: SecurityException) {
            e.printStackTrace()
            _connectionState.value = "Bluetooth permission required"
        } catch (e: Exception) {
            e.printStackTrace()
            closeSocket()
            _connectionState.value = "Disconnected"
        }
    }

    private suspend fun listenForMessages() = withContext(Dispatchers.IO) {
        val inputStream = activeSocket?.inputStream ?: return@withContext
        val buffer = ByteArray(1024)
        while (true) {
            try {
                val bytes = inputStream.read(buffer)
                if (bytes <= 0) {
                    closeSocket()
                    _connectionState.value = "Disconnected"
                    break
                } else {
                    val message = String(buffer, 0, bytes)
                    _receivedMessage.value = message
                }
            } catch (e: IOException) {
                e.printStackTrace()
                closeSocket()
                _connectionState.value = "Disconnected"
                break
            }
        }
    }

    suspend fun sendMessage(message: String) = withContext(Dispatchers.IO) {
        try {
            val socket = activeSocket
            if (socket == null || !socket.isConnected) {
                _connectionState.value = "Disconnected"
                return@withContext
            }
            socket.outputStream.write(message.toByteArray())
        } catch (e: IOException) {
            e.printStackTrace()
            closeSocket()
            _connectionState.value = "Disconnected"
        } catch (e: SecurityException) {
            e.printStackTrace()
            _connectionState.value = "Bluetooth permission required"
        }
    }

    // Get paired devices safely
    fun getPairedDevices(): List<PairedDevice> {
        if (!hasConnectPermission()) {
            _connectionState.value = "Bluetooth permission required"
            return emptyList()
        }
        if (adapter == null || !adapter.isEnabled) {
            _connectionState.value = "Bluetooth disabled"
            return emptyList()
        }
        return try {
            adapter.bondedDevices?.map { device ->
                val name = try { device.name ?: "Unknown Device" } catch (e: SecurityException) { "Unknown Device" }
                val address = try { device.address ?: "" } catch (e: SecurityException) { "" }
                PairedDevice(device = device, name = name, address = address)
            } ?: emptyList()
        } catch (e: SecurityException) {
            e.printStackTrace()
            _connectionState.value = "Bluetooth permission required"
            emptyList()
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }
}
