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

class MmsReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "PushBridgeMmsReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action == Telephony.Sms.Intents.WAP_PUSH_DELIVER_ACTION ||
            action == Telephony.Sms.Intents.WAP_PUSH_RECEIVED_ACTION) {

            val mimeType = intent.type
            if (mimeType == "application/vnd.wap.mms-message") {
                Log.i(TAG, "Incoming MMS/Group message broadcast received ($action).")

                if (!PermissionUtils.hasSmsReadPermission(context)) {
                    Log.w(TAG, "MMS broadcast ignored: READ_SMS permission not granted.")
                    return
                }

                val timestamp = System.currentTimeMillis()
                val mmsId = "mms_$timestamp"

                val payload = SmsPayload(
                    id = mmsId,
                    sender = "Group Chat / MMS",
                    body = "New MMS / Group Message received",
                    timestamp = timestamp
                )

                // 1. Push immediate MMS alert to Firebase
                PushBridgeFirebaseManager.pushIncomingSms(context, payload)

                // 2. Refresh entire SMS/MMS threads map
                PushBridgeFirebaseManager.syncAllSmsThreads(context)

                // 3. Fallback sync
                PushBridgeSyncManager.syncIncomingSms(context, payload)
            }
        }
    }
}
