package com.example.pushbridge.data

import android.content.Context
import android.util.Log
import com.example.pushbridge.model.NotificationPayload
import com.example.pushbridge.model.SmsPayload
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

object PushBridgeSyncManager {
    private const val TAG = "PushBridgeSync"
    private const val DEFAULT_RELAY_URL = "http://10.0.2.2:3000" // 10.0.2.2 is host localhost in Android emulator
    private val scope = CoroutineScope(Dispatchers.IO)

    fun syncNotification(context: Context, payload: NotificationPayload) {
        scope.launch {
            try {
                val json = JSONObject().apply {
                    put("id", payload.id)
                    put("packageName", payload.packageName)
                    put("appName", payload.appName)
                    put("title", payload.title)
                    put("text", payload.text)
                    put("subText", payload.subText ?: "")
                    put("isRcs", payload.isRcs)
                    put("isEncrypted", payload.isEncrypted)
                    put("isGroup", payload.isGroup)
                    put("conversationTitle", payload.conversationTitle ?: "")
                    put("timestamp", payload.timestamp)
                    put("sourceDeviceId", getDeviceId(context))
                }

                postToRelay("/api/sync/notification", json.toString())
            } catch (e: Exception) {
                Log.e(TAG, "Failed to sync notification: ${e.message}", e)
            }
        }
    }

    fun syncIncomingSms(context: Context, sms: SmsPayload) {
        scope.launch {
            try {
                val json = JSONObject().apply {
                    put("id", sms.id)
                    put("sender", sms.sender)
                    put("body", sms.body)
                    put("timestamp", sms.timestamp)
                    put("sourceDeviceId", getDeviceId(context))
                    put("type", "sms")
                    put("isRcs", sms.isRcs)
                    put("isEncrypted", sms.isEncrypted)
                    put("protocol", sms.protocol)
                    put("isGroup", sms.isGroup)
                    put("groupTitle", sms.groupTitle ?: "")
                    put("recipients", org.json.JSONArray(sms.recipients))
                }

                postToRelay("/api/sync/notification", json.toString())
            } catch (e: Exception) {
                Log.e(TAG, "Failed to sync SMS: ${e.message}", e)
            }
        }
    }

    private fun postToRelay(endpoint: String, jsonBody: String) {
        val url = URL("$DEFAULT_RELAY_URL$endpoint")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 5000
            readTimeout = 5000
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=UTF-8")
        }

        try {
            OutputStreamWriter(conn.outputStream).use { writer ->
                writer.write(jsonBody)
                writer.flush()
            }
            val responseCode = conn.responseCode
            Log.d(TAG, "POST $endpoint -> Status code: $responseCode")
        } finally {
            conn.disconnect()
        }
    }

    fun getDeviceId(context: Context): String {
        val prefs = context.getSharedPreferences("pushbridge_prefs", Context.MODE_PRIVATE)
        var id = prefs.getString("device_id", null)
        if (id == null) {
            id = "android_" + java.util.UUID.randomUUID().toString().substring(0, 8)
            prefs.edit().putString("device_id", id).apply()
        }
        return id
    }
}
