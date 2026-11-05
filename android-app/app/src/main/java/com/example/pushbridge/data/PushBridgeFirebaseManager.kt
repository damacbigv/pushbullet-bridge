package com.example.pushbridge.data

import android.content.Context
import android.os.Build
import android.util.Log
import com.example.pushbridge.model.InboundCommand
import com.example.pushbridge.model.NotificationPayload
import com.example.pushbridge.model.SmsPayload
import com.example.pushbridge.util.NotificationReplyRegistry
import com.example.pushbridge.util.PermissionUtils
import com.example.pushbridge.util.ReplyResult
import com.example.pushbridge.util.SmsManagerHelper
import com.example.pushbridge.util.SmsSendResult
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.database.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.UUID

object PushBridgeFirebaseManager {
    private const val TAG = "PushBridgeFirebase"

    const val FIREBASE_PROJECT_ID = "pushclonebridge"
    const val FIREBASE_RTDB_URL = "https://pushclonebridge-default-rtdb.firebaseio.com"
    private const val FIREBASE_API_KEY = "AIzaSyCmoxdDpdYz_Gxsx04zXSemus6fJmA4-gU"
    private const val FIREBASE_APP_ID = "1:250338142494:web:5029dbee8c1e9da606810f"
    private const val FIREBASE_SENDER_ID = "250338142494"
    private const val FIREBASE_STORAGE_BUCKET = "pushclonebridge.firebasestorage.app"

    private val scope = CoroutineScope(Dispatchers.IO)
    private var isInitialized = false
    private var database: FirebaseDatabase? = null
    private var inboundListener: ChildEventListener? = null
    private var inboundRef: DatabaseReference? = null

    /**
     * Initializes the Firebase Android SDK programmatically using provided configuration credentials.
     */
    fun initialize(context: Context) {
        if (isInitialized) return

        try {
            val app = if (FirebaseApp.getApps(context).isEmpty()) {
                val options = FirebaseOptions.Builder()
                    .setApiKey(FIREBASE_API_KEY)
                    .setApplicationId(FIREBASE_APP_ID)
                    .setProjectId(FIREBASE_PROJECT_ID)
                    .setDatabaseUrl(FIREBASE_RTDB_URL)
                    .setGcmSenderId(FIREBASE_SENDER_ID)
                    .setStorageBucket(FIREBASE_STORAGE_BUCKET)
                    .build()
                FirebaseApp.initializeApp(context, options)
            } else {
                FirebaseApp.getInstance()
            }

            database = FirebaseDatabase.getInstance(app, FIREBASE_RTDB_URL)
            // Enable disk persistence for resilient offline-to-online sync
            try {
                database?.setPersistenceEnabled(true)
            } catch (_: Exception) {
                // Ignore if already set
            }

            isInitialized = true
            Log.i(TAG, "Firebase initialized successfully for project: $FIREBASE_PROJECT_ID")

            registerDeviceState(context)
            startInboundCommandListener(context)
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing Firebase: ${e.message}", e)
        }
    }

    /**
     * Registers current device metadata and permission states in Firebase.
     */
    fun registerDeviceState(context: Context) {
        val deviceId = getDeviceId(context)
        val deviceRef = database?.getReference("devices")?.child(deviceId)?.child("info") ?: return

        val deviceInfo = mapOf(
            "deviceId" to deviceId,
            "manufacturer" to Build.MANUFACTURER,
            "model" to Build.MODEL,
            "sdk" to Build.VERSION.SDK_INT,
            "hasNotificationAccess" to PermissionUtils.hasNotificationAccess(context),
            "hasSmsSendPermission" to PermissionUtils.hasSmsSendPermission(context),
            "hasSmsReadPermission" to PermissionUtils.hasSmsReadPermission(context),
            "hasContactsReadPermission" to PermissionUtils.hasContactsReadPermission(context),
            "lastSeen" to ServerValue.TIMESTAMP
        )

        deviceRef.setValue(deviceInfo)
            .addOnSuccessListener { Log.d(TAG, "Device state registered in Firebase") }
            .addOnFailureListener { e -> Log.w(TAG, "Failed to register device state: ${e.message}") }
    }

    /**
     * Pushes an intercepted notification JSON payload up to Firebase Realtime Database.
     */
    fun pushNotification(context: Context, payload: NotificationPayload) {
        scope.launch {
            if (!isInitialized) initialize(context)

            val deviceId = getDeviceId(context)
            val notifRef = database?.getReference("devices")
                ?.child(deviceId)
                ?.child("outbound_notifications")
                ?.child(payload.id)

            if (notifRef == null) {
                Log.w(TAG, "Firebase Database not ready. Falling back to local dispatch.")
                return@launch
            }

            notifRef.setValue(payload.toMap())
                .addOnSuccessListener {
                    Log.i(TAG, "Successfully synced notification '${payload.title}' from ${payload.appName} to Firebase")
                }
                .addOnFailureListener { err ->
                    Log.e(TAG, "Failed pushing notification to Firebase: ${err.message}", err)
                }
        }
    }

    /**
     * Pushes an intercepted incoming SMS up to Firebase.
     */
    fun pushIncomingSms(context: Context, sms: SmsPayload) {
        scope.launch {
            if (!isInitialized) initialize(context)

            val deviceId = getDeviceId(context)
            val smsRef = database?.getReference("devices")
                ?.child(deviceId)
                ?.child("outbound_sms")
                ?.child(sms.id) ?: return@launch

            smsRef.setValue(sms.toMap())
                .addOnSuccessListener {
                    Log.i(TAG, "Successfully synced SMS from ${sms.sender} to Firebase")
                }
                .addOnFailureListener { err ->
                    Log.e(TAG, "Failed syncing SMS to Firebase: ${err.message}", err)
                }
        }
    }

    /**
     * Reads SMS conversations from device Telephony database and syncs full message histories
     * and thread IDs to Firebase Realtime Database.
     */
    fun syncAllSmsThreads(context: Context, onComplete: ((Int) -> Unit)? = null) {
        scope.launch {
            if (!isInitialized) initialize(context)
            val deviceId = getDeviceId(context)
            val threads = SmsRepository.queryConversationThreads(context)
            Log.i(TAG, "Queried ${threads.size} SMS conversation threads for sync.")

            val threadsRef = database?.getReference("devices")?.child(deviceId)?.child("sms_threads") ?: return@launch

            val threadsMap = mutableMapOf<String, Any?>()
            for (thread in threads) {
                threadsMap[thread.threadId.toString()] = thread.toMap()
            }

            threadsRef.setValue(threadsMap)
                .addOnSuccessListener {
                    Log.i(TAG, "Successfully synced ${threads.size} SMS threads to Firebase.")
                    onComplete?.invoke(threads.size)
                }
                .addOnFailureListener { err ->
                    Log.e(TAG, "Failed syncing SMS threads to Firebase: ${err.message}", err)
                }
        }
    }

    /**
     * Listens for inbound commands (REMOTE_REPLY, SEND_SMS) from Chrome client or relay in real time.
     */
    fun startInboundCommandListener(context: Context) {
        val deviceId = getDeviceId(context)
        if (inboundRef != null) return // Already listening

        inboundRef = database?.getReference("devices")?.child(deviceId)?.child("inbound_commands")
        inboundListener = object : ChildEventListener {
            override fun onChildAdded(snapshot: DataSnapshot, previousChildName: String?) {
                processCommandSnapshot(context, snapshot)
            }

            override fun onChildChanged(snapshot: DataSnapshot, previousChildName: String?) {
                processCommandSnapshot(context, snapshot)
            }

            override fun onChildRemoved(snapshot: DataSnapshot) {}
            override fun onChildMoved(snapshot: DataSnapshot, previousChildName: String?) {}
            override fun onCancelled(error: DatabaseError) {
                Log.e(TAG, "Inbound command listener cancelled: ${error.message}")
            }
        }

        inboundRef?.addChildEventListener(inboundListener as ChildEventListener)
        Log.i(TAG, "Real-time inbound command listener active on devices/$deviceId/inbound_commands")
    }

    private fun processCommandSnapshot(context: Context, snapshot: DataSnapshot) {
        val status = snapshot.child("status").getValue(String::class.java) ?: "pending"
        if (status != "pending") return // Only process newly received pending commands

        val commandId = snapshot.key ?: return
        val type = snapshot.child("type").getValue(String::class.java) ?: ""

        Log.i(TAG, "Received inbound command: $type (id: $commandId)")

        // Mark as processing
        snapshot.ref.child("status").setValue("processing")

        when (type) {
            "REMOTE_REPLY" -> {
                val notificationKey = snapshot.child("notificationKey").getValue(String::class.java) ?: ""
                val replyText = snapshot.child("replyText").getValue(String::class.java) ?: ""

                if (notificationKey.isBlank() || replyText.isBlank()) {
                    updateCommandStatus(snapshot.ref, "failed", "Missing notificationKey or replyText")
                    return
                }

                val replyResult = NotificationReplyRegistry.executeRemoteReply(context, notificationKey, replyText)
                when (replyResult) {
                    is ReplyResult.Success -> {
                        updateCommandStatus(
                            snapshot.ref,
                            "completed",
                            "Remote reply executed natively for ${replyResult.packageName}"
                        )
                    }
                    is ReplyResult.Failure -> {
                        updateCommandStatus(snapshot.ref, "failed", replyResult.error)
                    }
                }
            }

            "SEND_SMS" -> {
                val recipient = snapshot.child("recipient").getValue(String::class.java) ?: ""
                val message = snapshot.child("message").getValue(String::class.java) ?: ""
                val threadIdVal = snapshot.child("threadId").getValue(Long::class.java) ?: 0L

                val smsResult = SmsManagerHelper.sendSms(context, recipient, message)
                when (smsResult) {
                    is SmsSendResult.Success -> {
                        updateCommandStatus(
                            snapshot.ref,
                            "completed",
                            "SMS sent successfully (${smsResult.partsCount} parts)"
                        )

                        // Update thread message in Firebase Realtime Database
                        val deviceId = getDeviceId(context)
                        val msgId = "out_${System.currentTimeMillis()}"
                        val targetThreadKey = if (threadIdVal > 0) threadIdVal.toString() else Math.abs(recipient.hashCode()).toString()

                        val msgMap = mapOf(
                            "id" to msgId,
                            "threadId" to threadIdVal,
                            "address" to recipient,
                            "body" to message,
                            "timestamp" to ServerValue.TIMESTAMP,
                            "isIncoming" to false,
                            "isRead" to true
                        )

                        database?.getReference("devices")?.child(deviceId)?.child("sms_threads")
                            ?.child(targetThreadKey)?.child("messages")?.child(msgId)?.setValue(msgMap)

                        database?.getReference("devices")?.child(deviceId)?.child("sms_threads")
                            ?.child(targetThreadKey)?.child("snippet")?.setValue(message)

                        database?.getReference("devices")?.child(deviceId)?.child("sms_threads")
                            ?.child(targetThreadKey)?.child("lastTimestamp")?.setValue(ServerValue.TIMESTAMP)

                        // Also trigger asynchronous thread refresh
                        syncAllSmsThreads(context)
                    }
                    is SmsSendResult.Failure -> {
                        updateCommandStatus(snapshot.ref, "failed", smsResult.error)
                    }
                }
            }

            "SYNC_SMS_THREADS" -> {
                syncAllSmsThreads(context) { count ->
                    updateCommandStatus(snapshot.ref, "completed", "Synced $count SMS threads to Firebase")
                }
            }

            "PING" -> {
                updateCommandStatus(snapshot.ref, "completed", "Pong from Android device")
            }

            else -> {
                updateCommandStatus(snapshot.ref, "failed", "Unknown command type: $type")
            }
        }
    }

    private fun updateCommandStatus(ref: DatabaseReference, status: String, resultMessage: String) {
        val updates = mapOf(
            "status" to status,
            "result" to resultMessage,
            "processedAt" to ServerValue.TIMESTAMP
        )
        ref.updateChildren(updates)
    }

    fun getDeviceId(context: Context): String {
        val prefs = context.getSharedPreferences("pushbridge_prefs", Context.MODE_PRIVATE)
        var id = prefs.getString("device_id", null)
        if (id == null) {
            id = "android_" + UUID.randomUUID().toString().substring(0, 8)
            prefs.edit().putString("device_id", id).apply()
        }
        return id
    }
}
