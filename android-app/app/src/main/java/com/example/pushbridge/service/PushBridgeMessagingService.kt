package com.example.pushbridge.service

import android.util.Log
import com.example.pushbridge.data.PushBridgeFirebaseManager
import com.example.pushbridge.util.NotificationReplyRegistry
import com.example.pushbridge.util.PermissionUtils
import com.example.pushbridge.util.ReplyResult
import com.example.pushbridge.util.SmsManagerHelper
import com.example.pushbridge.util.SmsSendResult
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

class PushBridgeMessagingService : FirebaseMessagingService() {

    companion object {
        private const val TAG = "PushBridgeFCM"
    }

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        Log.i(TAG, "New Firebase Cloud Messaging registration token generated: $token")

        val deviceId = PushBridgeFirebaseManager.getDeviceId(applicationContext)
        val database = FirebaseDatabase.getInstance(PushBridgeFirebaseManager.FIREBASE_RTDB_URL)
        database.getReference("devices").child(deviceId).child("info").child("fcmToken").setValue(token)
    }

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        super.onMessageReceived(remoteMessage)

        val data = remoteMessage.data
        if (data.isEmpty()) {
            Log.d(TAG, "Empty FCM message data payload received")
            return
        }

        val action = data["action"] ?: data["type"] ?: ""
        Log.i(TAG, "Inbound FCM command received: '$action'")

        when (action) {
            "REMOTE_REPLY" -> {
                val notificationKey = data["notificationKey"] ?: ""
                val replyText = data["replyText"] ?: ""

                if (notificationKey.isBlank() || replyText.isBlank()) {
                    Log.w(TAG, "Invalid REMOTE_REPLY payload: missing notificationKey or replyText")
                    return
                }

                val result = NotificationReplyRegistry.executeRemoteReply(applicationContext, notificationKey, replyText)
                when (result) {
                    is ReplyResult.Success -> {
                        Log.i(TAG, "FCM Remote reply executed successfully for ${result.packageName}")
                    }
                    is ReplyResult.Failure -> {
                        Log.e(TAG, "FCM Remote reply failed: ${result.error}")
                    }
                }
            }

            "SEND_SMS" -> {
                val recipient = data["recipient"] ?: ""
                val message = data["message"] ?: ""

                if (!PermissionUtils.hasSmsSendPermission(applicationContext)) {
                    Log.e(TAG, "FCM SEND_SMS failed: SEND_SMS permission has not been granted.")
                    return
                }

                val smsResult = SmsManagerHelper.sendSms(applicationContext, recipient, message)
                when (smsResult) {
                    is SmsSendResult.Success -> {
                        Log.i(TAG, "FCM Outbound SMS dispatched to ${smsResult.recipient}")
                    }
                    is SmsSendResult.Failure -> {
                        Log.e(TAG, "FCM Outbound SMS failed: ${smsResult.error}")
                    }
                }
            }

            else -> {
                Log.w(TAG, "Unhandled FCM action: $action")
            }
        }
    }
}
