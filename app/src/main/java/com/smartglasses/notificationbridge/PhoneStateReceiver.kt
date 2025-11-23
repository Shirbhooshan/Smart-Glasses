
package com.smartglasses.notificationbridge

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.provider.ContactsContract
import android.telephony.TelephonyManager
import androidx.core.app.ActivityCompat

class PhoneStateReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == TelephonyManager.ACTION_PHONE_STATE_CHANGED) {
            val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE)

            if (state == TelephonyManager.EXTRA_STATE_RINGING) {
                val incomingNumber = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)
                val contactName = getContactName(context, incomingNumber)

                val message = "CALL: ${contactName ?: incomingNumber ?: "Unknown"}"
                BluetoothService.sendMessage(message)
            }
        }
    }

    private fun getContactName(context: Context, phoneNumber: String?): String? {
        if (phoneNumber == null) return null

        if (ActivityCompat.checkSelfPermission(context, android.Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            return null
        }

        var contactName: String? = null
        val uri = android.net.Uri.withAppendedPath(
            ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
            android.net.Uri.encode(phoneNumber)
        )

        val cursor: Cursor? = context.contentResolver.query(
            uri,
            arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME),
            null,
            null,
            null
        )

        cursor?.use {
            if (it.moveToFirst()) {
                contactName = it.getString(it.getColumnIndexOrThrow(ContactsContract.PhoneLookup.DISPLAY_NAME))
            }
        }

        return contactName
    }
}