package com.example.pushbridge.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log
import com.example.pushbridge.data.PushBridgeFirebaseManager
import com.example.pushbridge.data.PushBridgeSyncManager
import com.example.pushbridge.model.SmsPayload
import com.example.pushbridge.util.PermissionUtils

class SmsReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "PushBridgeSmsReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Telephony.Sms.Intents.SMS_RECEIVED_ACTION) {
            // Verify runtime permission
            if (!PermissionUtils.hasSmsReadPermission(context)) {
                Log.w(TAG, "SMS received broadcast ignored: READ_SMS permission not granted.")
                return
            }

            val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
            if (messages.isNullOrEmpty()) return

            val sender = messages[0].displayOriginatingAddress ?: "Unknown Sender"
            val fullBody = StringBuilder()
            var timestamp = System.currentTimeMillis()

            for (sms in messages) {
                fullBody.append(sms.displayMessageBody)
                timestamp = sms.timestampMillis
            }

            val body = fullBody.toString()
            val smsId = "sms_${timestamp}_${Math.abs(sender.hashCode())}"

            Log.i(TAG, "Incoming SMS intercepted from $sender: '$body'")

            val payload = SmsPayload(
                id = smsId,
                sender = sender,
                body = body,
                timestamp = timestamp
            )

            // 1. Instant push to Firebase Realtime Database
            PushBridgeFirebaseManager.pushIncomingSms(context, payload)

            // 2. Fallback push to Relay Server
            PushBridgeSyncManager.syncIncomingSms(context, payload)
        }
    }
}
