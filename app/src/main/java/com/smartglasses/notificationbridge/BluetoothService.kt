package com.smartglasses.notificationbridge

import android.app.*
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.*
import java.util.concurrent.LinkedBlockingQueue

class BluetoothService : Service() {

    private var bluetoothAdapter: BluetoothAdapter? = null
    private var bluetoothSocket: BluetoothSocket? = null
    private var outputStream: OutputStream? = null
    private var inputStream: InputStream? = null
    private var connectionThread: Thread? = null
    private var readerThread: Thread? = null
    private val messageQueue = LinkedBlockingQueue<String>()

    companion object {
        private const val TAG = "BluetoothService"

        const val ACTION_CONNECT = "com.smartglasses.ACTION_CONNECT"
        const val ACTION_DISCONNECT = "com.smartglasses.ACTION_DISCONNECT"
        const val ACTION_SEND_MESSAGE = "com.smartglasses.ACTION_SEND_MESSAGE"
        const val ACTION_CONNECTION_STATE_CHANGED = "com.smartglasses.ACTION_CONNECTION_STATE_CHANGED"

        const val EXTRA_DEVICE = "device"
        const val EXTRA_MESSAGE = "message"
        const val EXTRA_STATE = "state"

        const val STATE_DISCONNECTED = 0
        const val STATE_CONNECTING = 1
        const val STATE_CONNECTED = 2

        private const val SPP_UUID = "00001101-0000-1000-8000-00805F9B34FB"
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "SmartGlassesService"

        private var instance: BluetoothService? = null
        private var currentState = STATE_DISCONNECTED

        fun sendMessage(message: String) {
            instance?.queueMessage(message)
        }

        fun getCurrentState(): Int {
            return currentState
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this

        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter

        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification("Ready", "Tap to open app"))

        startMessageSender()
        Log.d(TAG, "BluetoothService created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CONNECT -> {
                val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(EXTRA_DEVICE, BluetoothDevice::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(EXTRA_DEVICE)
                }
                device?.let { connectToDevice(it) }
            }
            ACTION_DISCONNECT -> disconnect()
            ACTION_SEND_MESSAGE -> {
                val message = intent.getStringExtra(EXTRA_MESSAGE)
                message?.let { queueMessage(it) }
            }
        }
        return START_STICKY
    }

    private fun connectToDevice(device: BluetoothDevice) {
        Log.d(TAG, "\n========================================")
        Log.d(TAG, "Connection Request")
        Log.d(TAG, "========================================")

        if (ActivityCompat.checkSelfPermission(this, android.Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "❌ Missing BLUETOOTH_CONNECT permission")
            setStateAndBroadcast(STATE_DISCONNECTED)
            updateNotification("Permission Denied", "Grant Bluetooth permission")
            return
        }

        Log.d(TAG, "Device Name: ${device.name}")
        Log.d(TAG, "Device Address: ${device.address}")
        Log.d(TAG, "Device Type: ${device.type}")
        Log.d(TAG, "Bond State: ${device.bondState}")

        // Check if device is paired
        if (device.bondState != BluetoothDevice.BOND_BONDED) {
            Log.e(TAG, "❌ Device is not paired! Pair it in phone settings first.")
            setStateAndBroadcast(STATE_DISCONNECTED)
            updateNotification("Not Paired", "Pair ESP32 in Bluetooth settings")
            return
        }

        Log.d(TAG, "✓ Device is paired")

        // Cancel any ongoing discovery
        try {
            if (bluetoothAdapter?.isDiscovering == true) {
                bluetoothAdapter?.cancelDiscovery()
                Log.d(TAG, "✓ Discovery cancelled")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error cancelling discovery: ${e.message}")
        }

        // Disconnect existing connection
        disconnect()

        // Wait a bit before connecting
        Thread.sleep(1000)

        setStateAndBroadcast(STATE_CONNECTING)
        updateNotification("Connecting...", "Connecting to ${device.name}")

        connectionThread = Thread {
            var attemptNumber = 1
            var connected = false

            while (attemptNumber <= 3 && !connected) {
                try {
                    Log.d(TAG, "\n--- Attempt $attemptNumber/3 ---")

                    if (attemptNumber == 1) {
                        // Method 1: Standard secure connection
                        Log.d(TAG, "Trying standard secure RFCOMM...")
                        val uuid = UUID.fromString(SPP_UUID)
                        bluetoothSocket = device.createRfcommSocketToServiceRecord(uuid)
                    } else if (attemptNumber == 2) {
                        // Method 2: Insecure connection
                        Log.d(TAG, "Trying insecure RFCOMM...")
                        val uuid = UUID.fromString(SPP_UUID)
                        bluetoothSocket = device.createInsecureRfcommSocketToServiceRecord(uuid)
                    } else {
                        // Method 3: Fallback using reflection
                        Log.d(TAG, "Trying fallback method (reflection)...")
                        val method = device.javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
                        bluetoothSocket = method.invoke(device, 1) as BluetoothSocket
                    }

                    Log.d(TAG, "Socket created, connecting...")
                    bluetoothSocket?.connect()

                    Log.d(TAG, "✓✓✓ CONNECTED! ✓✓✓")
                    connected = true

                    outputStream = bluetoothSocket?.outputStream
                    inputStream = bluetoothSocket?.inputStream

                    Log.d(TAG, "✓ Streams obtained")

                    setStateAndBroadcast(STATE_CONNECTED)
                    updateNotification("Connected", "Connected to ${device.name}")

                    // Start reading thread
                    startReaderThread()

                    // Send test message
                    Thread.sleep(500)
                    sendMessageNow("TEST:Connection established from Android")

                    Log.d(TAG, "========================================")
                    Log.d(TAG, "Connection successful!")
                    Log.d(TAG, "========================================\n")

                } catch (e: IOException) {
                    Log.w(TAG, "Attempt $attemptNumber failed: ${e.message}")

                    try {
                        bluetoothSocket?.close()
                    } catch (closeException: IOException) {
                        Log.e(TAG, "Error closing socket: ${closeException.message}")
                    }

                    if (attemptNumber < 3) {
                        Log.d(TAG, "Waiting 2 seconds before retry...")
                        Thread.sleep(2000)
                    }

                    attemptNumber++
                } catch (e: Exception) {
                    Log.e(TAG, "Unexpected error: ${e.message}", e)
                    attemptNumber++
                }
            }

            if (!connected) {
                Log.e(TAG, "\n========================================")
                Log.e(TAG, "❌ ALL CONNECTION ATTEMPTS FAILED")
                Log.e(TAG, "========================================\n")

                setStateAndBroadcast(STATE_DISCONNECTED)
                updateNotification("Connection Failed", "Check ESP32 is powered on and paired")
            }
        }
        connectionThread?.start()
    }

    private fun startReaderThread() {
        readerThread = Thread {
            val buffer = ByteArray(1024)
            var bytes: Int

            Log.d(TAG, "Reader thread started")

            try {
                while (currentState == STATE_CONNECTED && inputStream != null) {
                    bytes = inputStream!!.read(buffer)
                    if (bytes > 0) {
                        val message = String(buffer, 0, bytes)
                        Log.d(TAG, "📩 Received from ESP32: $message")
                    }
                }
            } catch (e: IOException) {
                Log.e(TAG, "Reader thread error: ${e.message}")
                if (currentState == STATE_CONNECTED) {
                    setStateAndBroadcast(STATE_DISCONNECTED)
                    updateNotification("Disconnected", "Connection lost")
                }
            }

            Log.d(TAG, "Reader thread stopped")
        }
        readerThread?.start()
    }

    private fun disconnect() {
        Log.d(TAG, "Disconnecting...")

        connectionThread?.interrupt()
        readerThread?.interrupt()

        connectionThread = null
        readerThread = null

        try {
            inputStream?.close()
            outputStream?.close()
            bluetoothSocket?.close()
            Log.d(TAG, "✓ Socket closed")
        } catch (e: IOException) {
            Log.e(TAG, "Error closing socket: ${e.message}")
        }

        inputStream = null
        outputStream = null
        bluetoothSocket = null

        setStateAndBroadcast(STATE_DISCONNECTED)
        updateNotification("Disconnected", "Tap to open app")
    }

    private fun setStateAndBroadcast(state: Int) {
        val oldState = currentState
        currentState = state

        val stateNames = mapOf(
            STATE_DISCONNECTED to "DISCONNECTED",
            STATE_CONNECTING to "CONNECTING",
            STATE_CONNECTED to "CONNECTED"
        )

        Log.d(TAG, "State: ${stateNames[oldState]} → ${stateNames[state]}")

        val intent = Intent(ACTION_CONNECTION_STATE_CHANGED)
        intent.putExtra(EXTRA_STATE, state)
        sendBroadcast(intent)
    }

    private fun queueMessage(message: String) {
        messageQueue.offer(message)
        Log.d(TAG, "📤 Message queued: $message")
    }

    private fun startMessageSender() {
        Thread {
            while (true) {
                try {
                    val message = messageQueue.take()
                    sendMessageNow(message)
                } catch (e: InterruptedException) {
                    break
                } catch (e: Exception) {
                    Log.e(TAG, "Error in message sender: ${e.message}")
                }
            }
        }.start()
    }

    private fun sendMessageNow(message: String) {
        if (currentState == STATE_CONNECTED && outputStream != null) {
            try {
                val data = "$message\n".toByteArray(Charsets.UTF_8)
                outputStream?.write(data)
                outputStream?.flush()
                Log.d(TAG, "✓ Message sent: $message")
            } catch (e: IOException) {
                Log.e(TAG, "Error sending message: ${e.message}")
                setStateAndBroadcast(STATE_DISCONNECTED)
                updateNotification("Disconnected", "Connection lost")
            }
        } else {
            Log.w(TAG, "Cannot send message - not connected (state: $currentState)")
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Smart Glasses Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Bluetooth connection status"
            }
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(title: String, text: String): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_bluetooth)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification(title: String, text: String) {
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(NOTIFICATION_ID, createNotification(title, text))
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        disconnect()
        Log.d(TAG, "BluetoothService destroyed")
    }
}