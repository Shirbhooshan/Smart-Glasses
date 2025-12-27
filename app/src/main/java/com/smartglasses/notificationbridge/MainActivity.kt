package com.smartglasses.notificationbridge

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var bluetoothAdapter: BluetoothAdapter
    private lateinit var statusText: TextView
    private lateinit var connectedDeviceText: TextView
    private lateinit var scanButton: Button
    private lateinit var testButton: Button
    private lateinit var deviceSpinner: Spinner
    private lateinit var connectButton: Button
    private lateinit var disconnectButton: Button

    private var deviceList = mutableListOf<BluetoothDevice>()
    private val PERMISSION_REQUEST_CODE = 1001
    private val handler = Handler(Looper.getMainLooper())

    private val connectionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                BluetoothService.ACTION_CONNECTION_STATE_CHANGED -> {
                    val state = intent.getIntExtra(BluetoothService.EXTRA_STATE, BluetoothService.STATE_DISCONNECTED)
                    handler.post {
                        updateConnectionStatus(state)
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initializeViews()
        setupBluetoothAdapter()
        checkAndRequestPermissions()

        // Register receiver
        val filter = IntentFilter(BluetoothService.ACTION_CONNECTION_STATE_CHANGED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(connectionReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(connectionReceiver, filter)
        }

        // Check current connection state
        checkCurrentConnectionState()
    }

    private fun initializeViews() {
        statusText = findViewById(R.id.statusText)
        connectedDeviceText = findViewById(R.id.connectedDeviceText)
        scanButton = findViewById(R.id.scanButton)
        testButton = findViewById(R.id.testButton)
        deviceSpinner = findViewById(R.id.deviceSpinner)
        connectButton = findViewById(R.id.connectButton)
        disconnectButton = findViewById(R.id.disconnectButton)

        scanButton.setOnClickListener { startDeviceScan() }
        testButton.setOnClickListener { sendTestMessage() }
        connectButton.setOnClickListener { connectToSelectedDevice() }
        disconnectButton.setOnClickListener { disconnectDevice() }

        // Set initial state
        updateConnectionStatus(BluetoothService.STATE_DISCONNECTED)
    }

    private fun setupBluetoothAdapter() {
        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter

        if (bluetoothAdapter == null) {
            showDialog("Bluetooth Not Supported", "Your device doesn't support Bluetooth.")
            finish()
        }
    }

    private fun checkCurrentConnectionState() {
        // Poll for current state after a short delay
        handler.postDelayed({
            val state = BluetoothService.getCurrentState()
            updateConnectionStatus(state)
        }, 500)
    }

    private fun checkAndRequestPermissions() {
        val permissionsNeeded = mutableListOf<String>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                permissionsNeeded.add(Manifest.permission.BLUETOOTH_CONNECT)
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
                permissionsNeeded.add(Manifest.permission.BLUETOOTH_SCAN)
            }
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
            permissionsNeeded.add(Manifest.permission.READ_PHONE_STATE)
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CALL_LOG) != PackageManager.PERMISSION_GRANTED) {
            permissionsNeeded.add(Manifest.permission.READ_CALL_LOG)
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            permissionsNeeded.add(Manifest.permission.READ_CONTACTS)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                permissionsNeeded.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        if (permissionsNeeded.isNotEmpty()) {
            showDialog(
                "Permissions Needed",
                "This app needs permissions to work properly.",
                onPositive = {
                    ActivityCompat.requestPermissions(this, permissionsNeeded.toTypedArray(), PERMISSION_REQUEST_CODE)
                }
            )
        } else {
            checkNotificationAccess()
        }
    }

    private fun checkNotificationAccess() {
        val enabled = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
        if (enabled == null || !enabled.contains(packageName)) {
            showDialog(
                "Notification Access Needed",
                "Please enable notification access for this app.",
                onPositive = {
                    startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"))
                }
            )
        } else {
            checkBatteryOptimization()
        }
    }

    private fun checkBatteryOptimization() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val intent = Intent()
            val packageName = packageName
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                showDialog(
                    "Battery Optimization",
                    "Please disable battery optimization to keep the app running.",
                    onPositive = {
                        intent.action = Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
                        intent.data = android.net.Uri.parse("package:$packageName")
                        startActivity(intent)
                    }
                )
            }
        }
    }

    private fun startDeviceScan() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "Please grant Bluetooth permissions", Toast.LENGTH_SHORT).show()
                return
            }
        }

        deviceList.clear()
        val pairedDevices = bluetoothAdapter.bondedDevices
        deviceList.addAll(pairedDevices)

        val deviceNames = deviceList.map { it.name ?: "Unknown Device" }
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, deviceNames)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        deviceSpinner.adapter = adapter

        if (deviceList.isEmpty()) {
            Toast.makeText(this, "No paired devices found. Please pair ESP32 in Bluetooth settings.", Toast.LENGTH_LONG).show()
        } else {
            Toast.makeText(this, "Found ${deviceList.size} device(s)", Toast.LENGTH_SHORT).show()
        }
    }

    private fun connectToSelectedDevice() {
        val position = deviceSpinner.selectedItemPosition
        if (position >= 0 && position < deviceList.size) {
            val device = deviceList[position]

            // Show connecting immediately
            updateConnectionStatus(BluetoothService.STATE_CONNECTING)

            val intent = Intent(this, BluetoothService::class.java)
            intent.action = BluetoothService.ACTION_CONNECT
            intent.putExtra(BluetoothService.EXTRA_DEVICE, device)
            startForegroundService(intent)

            val prefs = getSharedPreferences("SmartGlasses", Context.MODE_PRIVATE)
            prefs.edit().putString("device_address", device.address).apply()
            prefs.edit().putString("device_name", device.name).apply()

            Toast.makeText(this, "Connecting to ${device.name}...", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Please select a device first", Toast.LENGTH_SHORT).show()
        }
    }

    private fun disconnectDevice() {
        // Show disconnected immediately
        updateConnectionStatus(BluetoothService.STATE_DISCONNECTED)

        val intent = Intent(this, BluetoothService::class.java)
        intent.action = BluetoothService.ACTION_DISCONNECT
        startService(intent)

        Toast.makeText(this, "Disconnected", Toast.LENGTH_SHORT).show()
    }

    private fun sendTestMessage() {
        BluetoothService.sendMessage("TEST: Hello from your phone!")
        Toast.makeText(this, "Test message sent!", Toast.LENGTH_SHORT).show()
    }

    private fun updateConnectionStatus(state: Int) {
        when (state) {
            BluetoothService.STATE_CONNECTED -> {
                // CONNECTED - GREEN
                statusText.text = "🔵 Connected"
                statusText.setTextColor(ContextCompat.getColor(this, R.color.connected_color))

                val prefs = getSharedPreferences("SmartGlasses", Context.MODE_PRIVATE)
                val deviceName = prefs.getString("device_name", "ESP32_Glasses") ?: "ESP32_Glasses"
                connectedDeviceText.text = "Device: $deviceName"

                testButton.isEnabled = true
                disconnectButton.isEnabled = true
                connectButton.isEnabled = false

                // Change button colors
                connectButton.alpha = 0.5f
                disconnectButton.alpha = 1.0f
            }
            BluetoothService.STATE_CONNECTING -> {
                // CONNECTING - ORANGE
                statusText.text = "🔄 Connecting..."
                statusText.setTextColor(ContextCompat.getColor(this, R.color.connecting_color))
                connectedDeviceText.text = "Please wait..."

                testButton.isEnabled = false
                disconnectButton.isEnabled = false
                connectButton.isEnabled = false

                connectButton.alpha = 0.5f
                disconnectButton.alpha = 0.5f
            }
            else -> {
                // DISCONNECTED - GREY
                statusText.text = "⚪ Disconnected"
                statusText.setTextColor(ContextCompat.getColor(this, R.color.disconnected_color))
                connectedDeviceText.text = "No device connected"

                testButton.isEnabled = false
                disconnectButton.isEnabled = false
                connectButton.isEnabled = true

                connectButton.alpha = 1.0f
                disconnectButton.alpha = 0.5f
            }
        }
    }

    private fun showDialog(title: String, message: String, onPositive: (() -> Unit)? = null) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("OK") { _, _ -> onPositive?.invoke() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun onResume() {
        super.onResume()
        // Check state when returning to app
        checkCurrentConnectionState()
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(connectionReceiver)
        } catch (e: Exception) {
            // Already unregistered
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE) {
            val allGranted = grantResults.all { it == PackageManager.PERMISSION_GRANTED }
            if (allGranted) {
                checkNotificationAccess()
            } else {
                Toast.makeText(this, "Some permissions were denied.", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun connectToSelectedDevice() {
        val position = deviceSpinner.selectedItemPosition
        if (position >= 0 && position < deviceList.size) {
            val device = deviceList[position]

            // Force close any existing connections
            try {
                val method = BluetoothAdapter::class.java.getMethod("cancelDiscovery")
                method.invoke(bluetoothAdapter)
            } catch (e: Exception) {
                // Ignore
            }

            // Small delay
            Handler(Looper.getMainLooper()).postDelayed({
                updateConnectionStatus(BluetoothService.STATE_CONNECTING)

                val intent = Intent(this, BluetoothService::class.java)
                intent.action = BluetoothService.ACTION_CONNECT
                intent.putExtra(BluetoothService.EXTRA_DEVICE, device)
                startForegroundService(intent)

                val prefs = getSharedPreferences("SmartGlasses", Context.MODE_PRIVATE)
                prefs.edit().putString("device_address", device.address).apply()
                prefs.edit().putString("device_name", device.name).apply()

                Toast.makeText(this, "Connecting to ${device.name}...", Toast.LENGTH_SHORT).show()
            }, 500)
        }
    }
}
