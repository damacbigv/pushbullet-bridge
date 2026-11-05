package com.example.pushbridge.util

import android.content.Context
import android.os.Build
import android.telephony.SmsManager
import android.util.Log

sealed class SmsSendResult {
    data class Success(val recipient: String, val partsCount: Int) : SmsSendResult()
    data class Failure(val recipient: String, val error: String, val throwable: Throwable? = null) : SmsSendResult()
}

object SmsManagerHelper {
    private const val TAG = "SmsManagerHelper"

    /**
     * Sends an SMS text message to the specified recipient with permission verification.
     */
    fun sendSms(context: Context, destinationAddress: String, textMessage: String): SmsSendResult {
        // 1. Verify runtime permission
        if (!PermissionUtils.hasSmsSendPermission(context)) {
            val errorMsg = "Permission Denied: SEND_SMS runtime permission has not been granted on this Android device."
            Log.e(TAG, errorMsg)
            return SmsSendResult.Failure(destinationAddress, errorMsg)
        }

        if (destinationAddress.isBlank() || textMessage.isBlank()) {
            return SmsSendResult.Failure(destinationAddress, "Recipient and message cannot be empty.")
        }

        return try {
            val smsManager: SmsManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                context.getSystemService(SmsManager::class.java)
            } else {
                @Suppress("DEPRECATION")
                SmsManager.getDefault()
            }

            // Handle multi-part SMS messages exceeding 160 characters
            val parts = smsManager.divideMessage(textMessage)
            if (parts.size > 1) {
                smsManager.sendMultipartTextMessage(destinationAddress, null, parts, null, null)
                Log.i(TAG, "Multipart SMS (${parts.size} parts) dispatched to $destinationAddress")
            } else {
                smsManager.sendTextMessage(destinationAddress, null, textMessage, null, null)
                Log.i(TAG, "Single SMS dispatched to $destinationAddress")
            }

            SmsSendResult.Success(destinationAddress, parts.size)
        } catch (e: Exception) {
            Log.e(TAG, "Exception while sending SMS to $destinationAddress: ${e.message}", e)
            SmsSendResult.Failure(destinationAddress, e.message ?: "Failed to dispatch SMS", e)
        }
    }
}
