package com.smartglasses.notificationbridge

import android.content.Intent
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log

class NotificationListener : NotificationListenerService() {

    companion object {
        private const val TAG = "NotificationListener"
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        try {
            val packageName = sbn.packageName
            val notification = sbn.notification
            val extras = notification.extras

            val appName = getAppName(packageName)
            val title = extras.getString("android.title", "")
            val text = extras.getCharSequence("android.text", "")?.toString() ?: ""

            // Skip system notifications
            if (packageName == "android" || packageName.startsWith("com.android")) {
                return
            }

            // Determine message type
            val message = when {
                isMessagingApp(packageName) && text.isNotEmpty() -> {
                    "MSG: $appName: $text"
                }
                title.isNotEmpty() -> {
                    "NOTIF: $appName: $title"
                }
                else -> {
                    "NOTIF: $appName: New notification"
                }
            }

            Log.d(TAG, "Sending notification: $message")
            BluetoothService.sendMessage(message)

        } catch (e: Exception) {
            Log.e(TAG, "Error processing notification", e)
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        // Not needed for this use case
    }

    private fun getAppName(packageName: String): String {
        return try {
            val appInfo = packageManager.getApplicationInfo(packageName, 0)
            packageManager.getApplicationLabel(appInfo).toString()
        } catch (e: Exception) {
            packageName
        }
    }

    private fun isMessagingApp(packageName: String): Boolean {
        val messagingApps = listOf(
            "com.whatsapp",
            "com.facebook.orca",
            "com.instagram.android",
            "com.google.android.apps.messaging",
            "com.android.mms",
            "org.telegram.messenger",
            "com.snapchat.android",
            "com.twitter.android",
            "com.discord"
        )
        return messagingApps.any { packageName.contains(it) }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        Log.d(TAG, "Notification Listener connected")

        // Start Bluetooth service
        val intent = Intent(this, BluetoothService::class.java)
        startForegroundService(intent)
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        Log.d(TAG, "Notification Listener disconnected")
    }
}