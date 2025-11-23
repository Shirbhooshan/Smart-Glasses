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

    private var deviceList = mutableListOf<BluetoothDevice>()
    private val PERMISSION_REQUEST_CODE = 1001

    private val connectionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                BluetoothService.ACTION_CONNECTION_STATE_CHANGED -> {
                    val state = intent.getIntExtra(BluetoothService.EXTRA_STATE, BluetoothService.STATE_DISCONNECTED)
                    updateConnectionStatus(state)
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
    }

    private fun initializeViews() {
        statusText = findViewById(R.id.statusText)
        connectedDeviceText = findViewById(R.id.connectedDeviceText)
        scanButton = findViewById(R.id.scanButton)
        testButton = findViewById(R.id.testButton)
        deviceSpinner = findViewById(R.id.deviceSpinner)
        connectButton = findViewById(R.id.connectButton)

        scanButton.setOnClickListener { startDeviceScan() }
        testButton.setOnClickListener { sendTestMessage() }
        connectButton.setOnClickListener { connectToSelectedDevice() }
    }

    private fun setupBluetoothAdapter() {
        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter

        if (bluetoothAdapter == null) {
            showDialog("Bluetooth Not Supported", "Your device doesn't support Bluetooth. We're sorry, but this app won't work on this device.")
            finish()
        }
    }

    private fun checkAndRequestPermissions() {
        val permissionsNeeded = mutableListOf<String>()

        // Bluetooth permissions
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                permissionsNeeded.add(Manifest.permission.BLUETOOTH_CONNECT)
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
                permissionsNeeded.add(Manifest.permission.BLUETOOTH_SCAN)
            }
        }

        // Phone permissions
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
            permissionsNeeded.add(Manifest.permission.READ_PHONE_STATE)
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CALL_LOG) != PackageManager.PERMISSION_GRANTED) {
            permissionsNeeded.add(Manifest.permission.READ_CALL_LOG)
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            permissionsNeeded.add(Manifest.permission.READ_CONTACTS)
        }

        // Notification permission (Android 13+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                permissionsNeeded.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        if (permissionsNeeded.isNotEmpty()) {
            showDialog(
                "Permissions Needed",
                "This app needs a few permissions to work properly. We'll ask for them now. Don't worry, we only use them to send notifications to your glasses!",
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
                "We need access to your notifications to send them to your glasses. You'll be taken to settings to enable this. Just find 'Smart Glasses Bridge' and turn it on!",
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
                    "To keep the app running smoothly in the background, we need to disable battery optimization. This helps ensure you don't miss any notifications!",
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
                Toast.makeText(this, "Please grant Bluetooth permissions first", Toast.LENGTH_SHORT).show()
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
            Toast.makeText(this, "No paired devices found. Please pair your ESP32 in Bluetooth settings first.", Toast.LENGTH_LONG).show()
        } else {
            Toast.makeText(this, "Found ${deviceList.size} paired device(s)", Toast.LENGTH_SHORT).show()
        }
    }

    private fun connectToSelectedDevice() {
        val position = deviceSpinner.selectedItemPosition
        if (position >= 0 && position < deviceList.size) {
            val device = deviceList[position]
            val intent = Intent(this, BluetoothService::class.java)
            intent.action = BluetoothService.ACTION_CONNECT
            intent.putExtra(BluetoothService.EXTRA_DEVICE, device)
            startForegroundService(intent)

            // Save device address
            val prefs = getSharedPreferences("SmartGlasses", Context.MODE_PRIVATE)
            prefs.edit().putString("device_address", device.address).apply()

            Toast.makeText(this, "Connecting to ${device.name}...", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Please select a device first", Toast.LENGTH_SHORT).show()
        }
    }

    private fun sendTestMessage() {
        BluetoothService.sendMessage("TEST: Hello from your phone!")
        Toast.makeText(this, "Test message sent!", Toast.LENGTH_SHORT).show()
    }

    private fun updateConnectionStatus(state: Int) {
        runOnUiThread {
            when (state) {
                BluetoothService.STATE_CONNECTED -> {
                    statusText.text = "🔵 Connected"
                    statusText.setTextColor(getColor(R.color.connected_color))
                    val prefs = getSharedPreferences("SmartGlasses", Context.MODE_PRIVATE)
                    val deviceAddress = prefs.getString("device_address", "Unknown")
                    connectedDeviceText.text = "Device: $deviceAddress"
                    testButton.isEnabled = true
                }
                BluetoothService.STATE_CONNECTING -> {
                    statusText.text = "🔄 Connecting..."
                    statusText.setTextColor(getColor(R.color.connecting_color))
                    testButton.isEnabled = false
                }
                else -> {
                    statusText.text = "⚪ Disconnected"
                    statusText.setTextColor(getColor(R.color.disconnected_color))
                    connectedDeviceText.text = "No device connected"
                    testButton.isEnabled = false
                }
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

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(connectionReceiver)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE) {
            val allGranted = grantResults.all { it == PackageManager.PERMISSION_GRANTED }
            if (allGranted) {
                checkNotificationAccess()
            } else {
                Toast.makeText(this, "Some permissions were denied. The app may not work properly.", Toast.LENGTH_LONG).show()
            }
        }
    }
}