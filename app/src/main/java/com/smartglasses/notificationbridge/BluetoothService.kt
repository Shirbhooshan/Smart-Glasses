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
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import java.io.IOException
import java.io.OutputStream
import java.util.*
import java.util.concurrent.LinkedBlockingQueue

class BluetoothService : Service() {

    private var bluetoothAdapter: BluetoothAdapter? = null
    private var bluetoothSocket: BluetoothSocket? = null
    private var outputStream: OutputStream? = null
    private var connectionThread: Thread? = null
    private val messageQueue = LinkedBlockingQueue<String>()

    companion object {
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
        disconnect()
        Thread.sleep(100)  // ← Added: Small delay

        setStateAndBroadcast(STATE_CONNECTING)
        updateNotification("Connecting...", "Connecting to ${device.name}")

        connectionThread = Thread {
            try {
                if (ActivityCompat.checkSelfPermission(this, android.Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                    setStateAndBroadcast(STATE_DISCONNECTED)
                    return@Thread
                }

                val uuid = UUID.fromString(SPP_UUID)
                bluetoothSocket = device.createRfcommSocketToServiceRecord(uuid)

                try {
                    bluetoothSocket?.connect()
                } catch (e: IOException) {
                    // ← Added: Fallback connection method
                    try {
                        val method = device.javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
                        bluetoothSocket?.close()
                        bluetoothSocket = method.invoke(device, 1) as BluetoothSocket
                        bluetoothSocket?.connect()
                    } catch (e2: Exception) {
                        throw e
                    }
                }

                outputStream = bluetoothSocket?.outputStream

                setStateAndBroadcast(STATE_CONNECTED)
                updateNotification("Connected", "Connected to ${device.name}")

            } catch (e: IOException) {
                e.printStackTrace()
                setStateAndBroadcast(STATE_DISCONNECTED)
                updateNotification("Connection Failed", "Tap to retry")
            }
        }
        connectionThread?.start()
    }

    private fun disconnect() {
        try {
            outputStream?.close()
            bluetoothSocket?.close()
        } catch (e: IOException) {
            e.printStackTrace()
        }
        outputStream = null
        bluetoothSocket = null
        setStateAndBroadcast(STATE_DISCONNECTED)
        updateNotification("Disconnected", "Tap to open app")
    }

    private fun setStateAndBroadcast(state: Int) {
        currentState = state
        val intent = Intent(ACTION_CONNECTION_STATE_CHANGED)
        intent.putExtra(EXTRA_STATE, state)
        sendBroadcast(intent)
    }

    private fun queueMessage(message: String) {
        messageQueue.offer(message)
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
                    e.printStackTrace()
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
            } catch (e: IOException) {
                e.printStackTrace()
                setStateAndBroadcast(STATE_DISCONNECTED)
                updateNotification("Disconnected", "Connection lost")
            }
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
    }
}