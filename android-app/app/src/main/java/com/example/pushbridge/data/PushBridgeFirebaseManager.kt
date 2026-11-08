package com.example.pushbridge.data

import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.util.Log
import com.example.pushbridge.BuildConfig
import com.example.pushbridge.model.DeviceRole
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
import kotlinx.coroutines.*
import java.util.UUID

object PushBridgeFirebaseManager {
    private const val TAG = "PushBridgeFirebase"

    val FIREBASE_PROJECT_ID: String = BuildConfig.FIREBASE_PROJECT_ID
    val FIREBASE_RTDB_URL: String = BuildConfig.FIREBASE_RTDB_URL

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var isInitialized = false
    private var database: FirebaseDatabase? = null
    private var inboundListener: ChildEventListener? = null
    private var inboundRef: DatabaseReference? = null
    private var heartbeatJob: Job? = null

    // Multi-device tablet peer listeners
    private var tabletPeerThreadsListener: ValueEventListener? = null
    private var tabletPeerNotifsListener: ChildEventListener? = null
    var activePrimaryPhoneId: String? = null
        private set

    // Thread Pagination Limit (30, 50, or 100)
    var currentPaginationLimit: Int = 30

    /**
     * Detects whether the current device is a Primary Telephony Phone or a Wi-Fi Tablet Companion.
     */
    fun getDeviceRole(context: Context): DeviceRole {
        val hasTelephony = context.packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)
        val isLargeScreen = (context.resources.configuration.screenLayout and Configuration.SCREENLAYOUT_SIZE_MASK) >= Configuration.SCREENLAYOUT_SIZE_LARGE
        return if (!hasTelephony || isLargeScreen) {
            DeviceRole.WIFI_TABLET
        } else {
            DeviceRole.PRIMARY_PHONE
        }
    }

    /**
     * Initializes the Firebase Android SDK programmatically using provided configuration credentials
     * without embedding raw secrets in source code.
     */
    fun initialize(context: Context) {
        if (isInitialized) return

        try {
            val app = if (FirebaseApp.getApps(context).isEmpty()) {
                val options = try {
                    FirebaseOptions.fromResource(context)
                } catch (e: Exception) {
                    null
                } ?: FirebaseOptions.Builder()
                    .setApiKey(BuildConfig.FIREBASE_API_KEY)
                    .setApplicationId(BuildConfig.FIREBASE_APP_ID)
                    .setProjectId(BuildConfig.FIREBASE_PROJECT_ID)
                    .setDatabaseUrl(BuildConfig.FIREBASE_RTDB_URL)
                    .setGcmSenderId(BuildConfig.FIREBASE_SENDER_ID)
                    .setStorageBucket(BuildConfig.FIREBASE_STORAGE_BUCKET)
                    .build()
                FirebaseApp.initializeApp(context, options)
            } else {
                FirebaseApp.getInstance()
            }

            val targetRtdbUrl = app.options.databaseUrl?.takeIf { it.isNotBlank() } ?: BuildConfig.FIREBASE_RTDB_URL
            database = FirebaseDatabase.getInstance(app, targetRtdbUrl)
            try {
                database?.setPersistenceEnabled(true)
            } catch (_: Exception) {
                // Ignore if already enabled
            }

            isInitialized = true
            val role = getDeviceRole(context)
            Log.i(TAG, "Firebase initialized for project: ${app.options.projectId ?: BuildConfig.FIREBASE_PROJECT_ID} (Device Role: $role)")

            registerDeviceState(context)
            setupPresence(context)

            if (role == DeviceRole.PRIMARY_PHONE) {
                startInboundCommandListener(context)
            } else {
                startTabletPeerSync(context)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing Firebase: ${e.message}", e)
        }
    }

    /**
     * Sets up real-time device presence and connection status via `.info/connected`.
     * Automatically updates `devices/{deviceId}/presence/online` and registers `onDisconnect`.
     */
    fun setupPresence(context: Context) {
        val deviceId = getDeviceId(context)
        val role = getDeviceRole(context)
        val connectedRef = database?.getReference(".info/connected") ?: return
        val presenceRef = database?.getReference("devices")?.child(deviceId)?.child("presence") ?: return

        connectedRef.addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val connected = snapshot.getValue(Boolean::class.java) ?: false
                if (connected) {
                    val deviceName = if (role == DeviceRole.WIFI_TABLET) {
                        "${Build.MANUFACTURER} ${Build.MODEL} (Tablet Companion)"
                    } else {
                        "${Build.MANUFACTURER} ${Build.MODEL}"
                    }
                    val presenceMap = mapOf<String, Any>(
                        "online" to true,
                        "lastHeartbeat" to ServerValue.TIMESTAMP,
                        "deviceName" to deviceName,
                        "deviceRole" to role.name,
                        "deviceType" to if (role == DeviceRole.WIFI_TABLET) "tablet" else "phone",
                        "paginationLimit" to currentPaginationLimit
                    )
                    presenceRef.updateChildren(presenceMap)

                    // On disconnect, mark offline and record disconnection timestamp
                    presenceRef.child("online").onDisconnect().setValue(false)
                    presenceRef.child("lastHeartbeat").onDisconnect().setValue(ServerValue.TIMESTAMP)

                    Log.i(TAG, "Presence active: device $deviceId ($role) is ONLINE")

                    // Synchronize threads and contacts automatically on phone connect
                    if (role == DeviceRole.PRIMARY_PHONE) {
                        syncAllSmsThreads(context, currentPaginationLimit)
                        syncDeviceContacts(context)
                    }
                }
            }

            override fun onCancelled(error: DatabaseError) {
                Log.w(TAG, "Presence listener cancelled: ${error.message}")
            }
        })

        // Start 30-second heartbeat loop
        startHeartbeatLoop(context)
    }

    private fun startHeartbeatLoop(context: Context) {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            val deviceId = getDeviceId(context)
            val presenceRef = database?.getReference("devices")?.child(deviceId)?.child("presence")
            while (isActive) {
                delay(30_000L)
                try {
                    val heartbeatMap = mapOf<String, Any>(
                        "online" to true,
                        "lastHeartbeat" to ServerValue.TIMESTAMP
                    )
                    presenceRef?.updateChildren(heartbeatMap)
                } catch (e: Exception) {
                    Log.d(TAG, "Heartbeat ping error: ${e.message}")
                }
            }
        }
    }

    /**
     * Touches the presence heartbeat timestamp whenever device sends SMS, notifications, or syncs.
     */
    fun touchHeartbeat(context: Context) {
        try {
            val deviceId = getDeviceId(context)
            val presenceRef = database?.getReference("devices")?.child(deviceId)?.child("presence")
            val heartbeatMap = mapOf<String, Any>(
                "online" to true,
                "lastHeartbeat" to ServerValue.TIMESTAMP
            )
            presenceRef?.updateChildren(heartbeatMap)
        } catch (e: Exception) {
            Log.d(TAG, "touchHeartbeat error: ${e.message}")
        }
    }

    /**
     * Registers current device metadata and permission states in Firebase.
     */
    fun registerDeviceState(context: Context) {
        val deviceId = getDeviceId(context)
        val role = getDeviceRole(context)
        val deviceRef = database?.getReference("devices")?.child(deviceId)?.child("info") ?: return

        val deviceInfo = mapOf(
            "deviceId" to deviceId,
            "deviceRole" to role.name,
            "deviceType" to if (role == DeviceRole.WIFI_TABLET) "tablet" else "phone",
            "isTablet" to (role == DeviceRole.WIFI_TABLET),
            "manufacturer" to Build.MANUFACTURER,
            "model" to Build.MODEL,
            "sdk" to Build.VERSION.SDK_INT,
            "hasTelephony" to context.packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY),
            "hasNotificationAccess" to PermissionUtils.hasNotificationAccess(context),
            "hasSmsSendPermission" to PermissionUtils.hasSmsSendPermission(context),
            "hasSmsReadPermission" to PermissionUtils.hasSmsReadPermission(context),
            "hasContactsReadPermission" to PermissionUtils.hasContactsReadPermission(context),
            "paginationLimit" to currentPaginationLimit,
            "lastSeen" to ServerValue.TIMESTAMP
        )

        deviceRef.setValue(deviceInfo)
            .addOnSuccessListener { Log.d(TAG, "Device state registered in Firebase for $deviceId") }
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
                    touchHeartbeat(context)
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
                    touchHeartbeat(context)
                }
                .addOnFailureListener { err ->
                    Log.e(TAG, "Failed syncing SMS to Firebase: ${err.message}", err)
                }
        }
    }

    /**
     * Reads SMS & MMS conversations with scalable pagination (30, 50, 100 threads)
     * and syncs message histories to Firebase Realtime Database.
     */
    fun syncAllSmsThreads(
        context: Context,
        limit: Int = currentPaginationLimit,
        onComplete: ((Int) -> Unit)? = null
    ) {
        currentPaginationLimit = limit
        scope.launch {
            if (!isInitialized) initialize(context)
            val deviceId = getDeviceId(context)
            val threads = SmsRepository.queryConversationThreads(context, maxThreads = limit)
            Log.i(TAG, "Queried ${threads.size} SMS conversation threads (limit: $limit) for sync (including group threads).")

            val threadsRef = database?.getReference("devices")?.child(deviceId)?.child("sms_threads") ?: return@launch

            val threadsMap = mutableMapOf<String, Any?>()
            for (thread in threads) {
                threadsMap[thread.threadId.toString()] = thread.toMap()
            }

            threadsRef.setValue(threadsMap)
                .addOnSuccessListener {
                    Log.i(TAG, "Successfully synced ${threads.size} SMS threads (limit $limit) to Firebase.")
                    touchHeartbeat(context)
                    onComplete?.invoke(threads.size)
                }
                .addOnFailureListener { err ->
                    Log.e(TAG, "Failed syncing SMS threads to Firebase: ${err.message}", err)
                }
        }
    }

    /**
     * Synchronizes a single SMS conversation thread to Firebase Realtime Database.
     * Fast targeted sync for real-time group message updates.
     */
    fun syncSingleThread(context: Context, threadId: Long, onComplete: (() -> Unit)? = null) {
        if (threadId <= 0) return
        scope.launch {
            if (!isInitialized) initialize(context)
            val deviceId = getDeviceId(context)
            val threads = SmsRepository.queryConversationThreads(context, maxThreads = currentPaginationLimit)
            val target = threads.firstOrNull { it.threadId == threadId }
            if (target != null) {
                val threadRef = database?.getReference("devices")
                    ?.child(deviceId)
                    ?.child("sms_threads")
                    ?.child(threadId.toString()) ?: return@launch

                threadRef.setValue(target.toMap())
                    .addOnSuccessListener {
                        Log.i(TAG, "Successfully synced single thread $threadId (isGroup=${target.isGroup}) to Firebase.")
                        touchHeartbeat(context)
                        onComplete?.invoke()
                    }
                    .addOnFailureListener { err ->
                        Log.w(TAG, "Failed syncing single thread $threadId: ${err.message}")
                    }
            } else {
                syncAllSmsThreads(context)
            }
        }
    }

    /**
     * Synchronizes local device phonebook contacts to Firebase so the Chrome extension
     * and tablet can query them alongside Google Contacts.
     */
    fun syncDeviceContacts(context: Context, onComplete: ((Int) -> Unit)? = null) {
        scope.launch {
            if (!isInitialized) initialize(context)
            val deviceId = getDeviceId(context)
            val contacts = SmsRepository.queryDeviceContacts(context)
            if (contacts.isEmpty()) {
                onComplete?.invoke(0)
                return@launch
            }

            val contactsRef = database?.getReference("devices")?.child(deviceId)?.child("contacts") ?: return@launch
            val contactsMap = mutableMapOf<String, Any?>()
            contacts.forEach { item ->
                contactsMap[item.cleanDigits] = item.toMap()
            }

            contactsRef.setValue(contactsMap)
                .addOnSuccessListener {
                    Log.i(TAG, "Successfully synced ${contacts.size} device contacts to Firebase.")
                    touchHeartbeat(context)
                    onComplete?.invoke(contacts.size)
                }
                .addOnFailureListener { err ->
                    Log.w(TAG, "Failed syncing device contacts: ${err.message}")
                }
        }
    }

    /**
     * Listens for inbound commands dispatched by Chrome Extension or Wi-Fi Tablet.
     */
    private fun startInboundCommandListener(context: Context) {
        val deviceId = getDeviceId(context)
        if (inboundListener != null) return

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
                val isGroup = snapshot.child("isGroup").getValue(Boolean::class.java) ?: false
                val attachmentData = snapshot.child("attachment").getValue(String::class.java)

                // Extract recipient list for group broadcast
                val recipientsSnap = snapshot.child("recipients")
                val recipientsList = mutableListOf<String>()

                if (recipientsSnap.exists()) {
                    for (child in recipientsSnap.children) {
                        val p = child.getValue(String::class.java) ?: ""
                        if (p.isNotBlank()) recipientsList.add(p)
                    }
                }

                if (recipientsList.isEmpty() && recipient.isNotBlank()) {
                    recipientsList.addAll(recipient.split(",", ";").map { it.trim() }.filter { it.isNotBlank() })
                }

                if (isGroup || recipientsList.size > 1) {
                    // True Group Broadcast: iterate and dispatch to all participants simultaneously
                    var successCount = 0
                    val failures = mutableListOf<String>()

                    for (targetPhone in recipientsList) {
                        val res = SmsManagerHelper.sendSms(context, targetPhone, message)
                        if (res is SmsSendResult.Success) {
                            successCount++
                        } else if (res is SmsSendResult.Failure) {
                            failures.add("$targetPhone (${res.error})")
                        }
                    }

                    if (successCount > 0) {
                        updateCommandStatus(
                            snapshot.ref,
                            "completed",
                            "Group SMS broadcasted to $successCount of ${recipientsList.size} recipients"
                        )

                        // Update unified group thread message in Firebase
                        recordSentMessageInThread(
                            context = context,
                            threadIdVal = threadIdVal,
                            recipient = recipient,
                            message = message,
                            isGroup = true,
                            recipientsList = recipientsList,
                            attachmentData = attachmentData
                        )
                    } else {
                        updateCommandStatus(
                            snapshot.ref,
                            "failed",
                            "Failed broadcasting to group: ${failures.joinToString("; ")}"
                        )
                    }
                } else {
                    // Single recipient SMS
                    val singleRecipient = recipientsList.firstOrNull() ?: recipient
                    val smsResult = SmsManagerHelper.sendSms(context, singleRecipient, message)
                    when (smsResult) {
                        is SmsSendResult.Success -> {
                            updateCommandStatus(
                                snapshot.ref,
                                "completed",
                                "SMS sent successfully (${smsResult.partsCount} parts)"
                            )
                            recordSentMessageInThread(
                                context = context,
                                threadIdVal = threadIdVal,
                                recipient = singleRecipient,
                                message = message,
                                isGroup = false,
                                attachmentData = attachmentData
                            )
                        }
                        is SmsSendResult.Failure -> {
                            updateCommandStatus(snapshot.ref, "failed", smsResult.error)
                        }
                    }
                }
            }

            "SYNC_SMS_THREADS" -> {
                val limit = snapshot.child("limit").getValue(Int::class.java) ?: currentPaginationLimit
                syncAllSmsThreads(context, limit = limit) { count ->
                    updateCommandStatus(snapshot.ref, "completed", "Synced $count SMS threads (limit $limit) to Firebase")
                }
            }

            "SYNC_CONTACTS" -> {
                syncDeviceContacts(context)
                updateCommandStatus(snapshot.ref, "completed", "Device contacts synced")
            }

            "PING" -> {
                updateCommandStatus(snapshot.ref, "completed", "Pong from Android device")
            }

            else -> {
                updateCommandStatus(snapshot.ref, "failed", "Unknown command type: $type")
            }
        }
    }

    private fun recordSentMessageInThread(
        context: Context,
        threadIdVal: Long,
        recipient: String,
        message: String,
        isGroup: Boolean = false,
        recipientsList: List<String> = emptyList(),
        attachmentData: String? = null
    ) {
        val deviceId = getDeviceId(context)
        val msgId = "out_${System.currentTimeMillis()}"
        val targetThreadKey = if (threadIdVal > 0) threadIdVal.toString() else Math.abs(recipient.hashCode()).toString()

        val msgMap = mutableMapOf<String, Any?>(
            "id" to msgId,
            "threadId" to threadIdVal,
            "address" to recipient,
            "body" to message,
            "timestamp" to ServerValue.TIMESTAMP,
            "isIncoming" to false,
            "isRead" to true
        )

        if (!attachmentData.isNullOrBlank()) {
            msgMap["attachmentData"] = attachmentData
            msgMap["attachmentMime"] = "image/jpeg"
        }

        val threadRef = database?.getReference("devices")?.child(deviceId)?.child("sms_threads")?.child(targetThreadKey)
        threadRef?.child("messages")?.child(msgId)?.setValue(msgMap)
        threadRef?.child("snippet")?.setValue(if (message.isNotBlank()) message else "[Photo]")
        threadRef?.child("lastTimestamp")?.setValue(ServerValue.TIMESTAMP)

        if (isGroup) {
            threadRef?.child("isGroup")?.setValue(true)
            if (recipientsList.isNotEmpty()) {
                val formattedRecips = recipientsList.map { p ->
                    mapOf("phone" to p, "name" to p, "cleanDigits" to p.replace(Regex("\\D"), ""))
                }
                threadRef?.child("recipients")?.setValue(formattedRecips)
            }
        }

        // Asynchronously refresh threads
        syncAllSmsThreads(context, currentPaginationLimit)
    }

    // =========================================================================
    // MULTI-DEVICE WI-FI TABLET COMPANION PEER-SYNC
    // =========================================================================

    /**
     * Wi-Fi Tablet Peer-Sync: Listens to the primary phone's threads and notifications,
     * maintaining a full synchronized state on the tablet.
     */
    fun startTabletPeerSync(
        context: Context,
        onThreadsUpdated: ((Map<String, Any?>) -> Unit)? = null,
        onNotificationReceived: ((NotificationPayload) -> Unit)? = null
    ) {
        val devicesRef = database?.getReference("devices") ?: return

        devicesRef.addListenerForSingleValueEvent(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                // Find primary phone (starts with "android_" and is not our tablet)
                val currentTabletId = getDeviceId(context)
                for (child in snapshot.children) {
                    val devId = child.key ?: continue
                    if (devId.startsWith("android_") && devId != currentTabletId) {
                        activePrimaryPhoneId = devId
                        Log.i(TAG, "Tablet Companion paired with Primary Phone: $devId")
                        attachTabletListeners(context, devId, onThreadsUpdated, onNotificationReceived)
                        break
                    }
                }
            }

            override fun onCancelled(error: DatabaseError) {
                Log.w(TAG, "Failed discovering primary phone for tablet peer-sync: ${error.message}")
            }
        })
    }

    private fun attachTabletListeners(
        context: Context,
        phoneId: String,
        onThreadsUpdated: ((Map<String, Any?>) -> Unit)?,
        onNotificationReceived: ((NotificationPayload) -> Unit)?
    ) {
        val phoneRef = database?.getReference("devices")?.child(phoneId) ?: return

        // 1. Observe SMS threads from primary phone
        tabletPeerThreadsListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val threadsMap = mutableMapOf<String, Any?>()
                for (child in snapshot.children) {
                    threadsMap[child.key ?: ""] = child.value
                }
                Log.i(TAG, "Tablet Companion received ${threadsMap.size} synced threads from phone $phoneId")
                onThreadsUpdated?.invoke(threadsMap)
            }

            override fun onCancelled(error: DatabaseError) {
                Log.w(TAG, "Tablet threads listener cancelled: ${error.message}")
            }
        }
        phoneRef.child("sms_threads").addValueEventListener(tabletPeerThreadsListener as ValueEventListener)

        // 2. Observe Outbound Notifications from primary phone
        tabletPeerNotifsListener = object : ChildEventListener {
            override fun onChildAdded(snapshot: DataSnapshot, previousChildName: String?) {
                val notif = parseNotificationSnapshot(snapshot)
                if (notif != null) {
                    Log.i(TAG, "Tablet Companion mirrored notification from phone: ${notif.title}")
                    onNotificationReceived?.invoke(notif)
                }
            }

            override fun onChildChanged(snapshot: DataSnapshot, previousChildName: String?) {}
            override fun onChildRemoved(snapshot: DataSnapshot) {}
            override fun onChildMoved(snapshot: DataSnapshot, previousChildName: String?) {}
            override fun onCancelled(error: DatabaseError) {}
        }
        phoneRef.child("outbound_notifications").addChildEventListener(tabletPeerNotifsListener as ChildEventListener)
    }

    private fun parseNotificationSnapshot(snapshot: DataSnapshot): NotificationPayload? {
        val id = snapshot.child("id").getValue(String::class.java) ?: snapshot.key ?: return null
        val title = snapshot.child("title").getValue(String::class.java) ?: ""
        val text = snapshot.child("text").getValue(String::class.java) ?: ""
        val appName = snapshot.child("appName").getValue(String::class.java) ?: "App"
        val packageName = snapshot.child("packageName").getValue(String::class.java) ?: ""
        val notificationKey = snapshot.child("notificationKey").getValue(String::class.java) ?: id
        val canReply = snapshot.child("canReply").getValue(Boolean::class.java) ?: false
        val timestamp = snapshot.child("timestamp").getValue(Long::class.java) ?: System.currentTimeMillis()

        return NotificationPayload(
            id = id,
            notificationKey = notificationKey,
            packageName = packageName,
            appName = appName,
            title = title,
            text = text,
            canReply = canReply,
            timestamp = timestamp
        )
    }

    /**
     * Dispatches an SMS command from the Wi-Fi Tablet companion to the Primary Phone's inbound queue.
     */
    fun sendTabletSmsCommand(
        context: Context,
        targetPhoneId: String,
        recipient: String,
        messageBody: String,
        isGroup: Boolean = false,
        recipients: List<String> = emptyList(),
        threadId: Long = 0L,
        attachmentData: String? = null,
        onComplete: ((Boolean) -> Unit)? = null
    ) {
        val tabletId = getDeviceId(context)
        val commandRef = database?.getReference("devices")?.child(targetPhoneId)?.child("inbound_commands")?.push() ?: return

        val payload = mapOf(
            "type" to "SEND_SMS",
            "sourceDeviceId" to tabletId,
            "recipient" to recipient,
            "message" to messageBody,
            "isGroup" to isGroup,
            "recipients" to recipients,
            "threadId" to threadId,
            "attachment" to (attachmentData ?: ""),
            "timestamp" to ServerValue.TIMESTAMP,
            "status" to "pending"
        )

        commandRef.setValue(payload)
            .addOnSuccessListener {
                Log.i(TAG, "Tablet queued outbound SMS to primary phone $targetPhoneId")
                onComplete?.invoke(true)
            }
            .addOnFailureListener { err ->
                Log.e(TAG, "Failed queuing tablet SMS command: ${err.message}")
                onComplete?.invoke(false)
            }
    }

    /**
     * Dispatches a remote notification reply from the Wi-Fi Tablet to the Primary Phone.
     */
    fun sendTabletRemoteReply(
        context: Context,
        targetPhoneId: String,
        notificationKey: String,
        replyText: String,
        onComplete: ((Boolean) -> Unit)? = null
    ) {
        val tabletId = getDeviceId(context)
        val commandRef = database?.getReference("devices")?.child(targetPhoneId)?.child("inbound_commands")?.push() ?: return

        val payload = mapOf(
            "type" to "REMOTE_REPLY",
            "sourceDeviceId" to tabletId,
            "notificationKey" to notificationKey,
            "replyText" to replyText,
            "timestamp" to ServerValue.TIMESTAMP,
            "status" to "pending"
        )

        commandRef.setValue(payload)
            .addOnSuccessListener { onComplete?.invoke(true) }
            .addOnFailureListener { onComplete?.invoke(false) }
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
            val role = getDeviceRole(context)
            val prefix = if (role == DeviceRole.WIFI_TABLET) "tablet_" else "android_"
            id = prefix + UUID.randomUUID().toString().substring(0, 8)
            prefs.edit().putString("device_id", id).apply()
        }
        return id
    }
}
