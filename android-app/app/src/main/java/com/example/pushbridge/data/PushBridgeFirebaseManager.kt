package com.example.pushbridge.data

import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.util.Log
import com.example.pushbridge.BuildConfig
import com.example.pushbridge.model.ClipboardItem
import com.example.pushbridge.model.DeviceContactItem
import com.example.pushbridge.model.DeviceRole
import com.example.pushbridge.model.NotificationPayload
import com.example.pushbridge.model.PeerDeviceInfo
import com.example.pushbridge.model.SmsConversationThread
import com.example.pushbridge.model.SmsMessageItem
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

object PushBridgeFirebaseManager {
    private const val TAG = "PushBridgeFirebase"
    private const val PREFS_NAME = "pushbridge_prefs"
    private const val KEY_DEVICE_ID = "device_id"
    private const val KEY_THEME = "selected_theme"
    private const val KEY_PAGINATION_LIMIT = "pagination_limit"
    private const val KEY_MIRROR_LOCAL_NOTIFS = "mirror_local_notifications"
    private const val KEY_TARGET_DEVICE_ID = "selected_target_device_id"

    val FIREBASE_PROJECT_ID: String = BuildConfig.FIREBASE_PROJECT_ID
    val FIREBASE_RTDB_URL: String = BuildConfig.FIREBASE_RTDB_URL

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var isInitialized = false
    private var database: FirebaseDatabase? = null
    private var inboundListener: ChildEventListener? = null
    private var inboundRef: DatabaseReference? = null
    private var heartbeatJob: Job? = null

    // Multi-device peer sync listeners & callback legacy hooks
    private var tabletPeerThreadsListener: ValueEventListener? = null
    private var tabletPeerNotifsListener: ChildEventListener? = null
    private var activeTargetThreadsPair: Pair<DatabaseReference, ValueEventListener>? = null
    private var activeTargetNotifsPair: Pair<DatabaseReference, ValueEventListener>? = null
    private var activeTargetContactsPair: Pair<DatabaseReference, ValueEventListener>? = null
    private var activeTargetClipboardPair: Pair<DatabaseReference, ValueEventListener>? = null
    private val discoveryListeners = mutableListOf<Pair<DatabaseReference, ValueEventListener>>()
    private val discoveredInfoMap = ConcurrentHashMap<String, DataSnapshot>()
    private val discoveredPresenceMap = ConcurrentHashMap<String, DataSnapshot>()

    // Optimistic outbound messages held briefly so sent bubbles never flicker before server echo
    private val optimisticMessagesByThread = ConcurrentHashMap<Long, MutableList<SmsMessageItem>>()

    var activePrimaryPhoneId: String? = null
        private set

    // Thread Pagination Limit (30, 50, or 100)
    var currentPaginationLimit: Int = 30

    // =========================================================================
    // REACTIVE DASHBOARD STATEFLOWS (Used by Tablet & Phone Compose UI)
    // =========================================================================

    private val _selectedTheme = MutableStateFlow("midnight")
    val selectedTheme: StateFlow<String> = _selectedTheme.asStateFlow()

    private val _paginationLimitFlow = MutableStateFlow(30)
    val paginationLimitFlow: StateFlow<Int> = _paginationLimitFlow.asStateFlow()

    private val _mirrorLocalNotificationsFlow = MutableStateFlow(true)
    val mirrorLocalNotificationsFlow: StateFlow<Boolean> = _mirrorLocalNotificationsFlow.asStateFlow()

    private val _peerDevices = MutableStateFlow<List<PeerDeviceInfo>>(emptyList())
    val peerDevices: StateFlow<List<PeerDeviceInfo>> = _peerDevices.asStateFlow()

    private val _activeTargetDeviceId = MutableStateFlow<String?>(null)
    val activeTargetDeviceId: StateFlow<String?> = _activeTargetDeviceId.asStateFlow()

    private val _syncedThreads = MutableStateFlow<List<SmsConversationThread>>(emptyList())
    val syncedThreads: StateFlow<List<SmsConversationThread>> = _syncedThreads.asStateFlow()

    private val _syncedNotifications = MutableStateFlow<List<NotificationPayload>>(emptyList())
    val syncedNotifications: StateFlow<List<NotificationPayload>> = _syncedNotifications.asStateFlow()

    private val _syncedContacts = MutableStateFlow<List<DeviceContactItem>>(emptyList())
    val syncedContacts: StateFlow<List<DeviceContactItem>> = _syncedContacts.asStateFlow()

    private val _syncedClipboard = MutableStateFlow<ClipboardItem?>(null)
    val syncedClipboard: StateFlow<ClipboardItem?> = _syncedClipboard.asStateFlow()

    private val _isSyncingFromPeer = MutableStateFlow(false)
    val isSyncingFromPeer: StateFlow<Boolean> = _isSyncingFromPeer.asStateFlow()

    private val _dashboardStatusBanner = MutableStateFlow<String?>(null)
    val dashboardStatusBanner: StateFlow<String?> = _dashboardStatusBanner.asStateFlow()

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

    private fun loadPreferences(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val role = getDeviceRole(context)

        val savedTheme = prefs.getString(KEY_THEME, "midnight") ?: "midnight"
        _selectedTheme.value = savedTheme

        val savedLimit = prefs.getInt(KEY_PAGINATION_LIMIT, 30).let {
            if (it in setOf(30, 50, 100)) it else 30
        }
        currentPaginationLimit = savedLimit
        _paginationLimitFlow.value = savedLimit

        val defaultMirror = role != DeviceRole.WIFI_TABLET
        val savedMirror = prefs.getBoolean(KEY_MIRROR_LOCAL_NOTIFS, defaultMirror)
        _mirrorLocalNotificationsFlow.value = savedMirror
    }

    fun shouldMirrorLocalNotifications(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val role = getDeviceRole(context)
        val defaultMirror = role != DeviceRole.WIFI_TABLET
        return prefs.getBoolean(KEY_MIRROR_LOCAL_NOTIFS, defaultMirror)
    }

    fun setMirrorLocalNotifications(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_MIRROR_LOCAL_NOTIFS, enabled)
            .apply()
        _mirrorLocalNotificationsFlow.value = enabled
    }

    fun setTheme(context: Context, themeId: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_THEME, themeId)
            .apply()
        _selectedTheme.value = themeId
    }

    fun setPaginationLimit(context: Context, limit: Int) {
        val sanitized = if (limit in setOf(30, 50, 100)) limit else 30
        currentPaginationLimit = sanitized
        _paginationLimitFlow.value = sanitized
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_PAGINATION_LIMIT, sanitized)
            .apply()
    }

    /**
     * Initializes the Firebase Android SDK programmatically using provided configuration credentials
     * without embedding raw secrets in source code.
     */
    fun initialize(context: Context) {
        if (isInitialized) return

        val appContext = context.applicationContext
        loadPreferences(appContext)

        try {
            val app = if (FirebaseApp.getApps(appContext).isEmpty()) {
                val options = try {
                    FirebaseOptions.fromResource(appContext)
                } catch (_: Exception) {
                    null
                } ?: FirebaseOptions.Builder()
                    .setApiKey(BuildConfig.FIREBASE_API_KEY)
                    .setApplicationId(BuildConfig.FIREBASE_APP_ID)
                    .setProjectId(BuildConfig.FIREBASE_PROJECT_ID)
                    .setDatabaseUrl(BuildConfig.FIREBASE_RTDB_URL)
                    .setGcmSenderId(BuildConfig.FIREBASE_SENDER_ID)
                    .setStorageBucket(BuildConfig.FIREBASE_STORAGE_BUCKET)
                    .build()
                FirebaseApp.initializeApp(appContext, options)
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
            val role = getDeviceRole(appContext)
            Log.i(TAG, "Firebase initialized for project: ${app.options.projectId ?: BuildConfig.FIREBASE_PROJECT_ID} (Device Role: $role)")

            registerDeviceState(appContext)
            setupPresence(appContext)

            if (role == DeviceRole.PRIMARY_PHONE) {
                startInboundCommandListener(appContext)
            }
            // Start multi-device discovery & live dashboard peer sync for both tablets and phones
            startPeerDiscoveryAndSync(appContext)
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing Firebase: ${e.message}", e)
        }
    }

    /**
     * Discovers all device IDs via shallow REST (~60 bytes), watches only `/info` & `/presence`
     * per device, and streams `/sms_threads`, `/outbound_notifications`, `/contacts`, and
     * `/clipboard` for the currently selected active target device.
     */
    fun refreshDiscoveredDevices(context: Context) {
        startPeerDiscoveryAndSync(context.applicationContext)
    }

    private fun startPeerDiscoveryAndSync(context: Context) {
        val appContext = context.applicationContext
        scope.launch {
            if (!isInitialized) return@launch
            val selfId = getDeviceId(appContext)
            val discoveredIds = mutableSetOf(selfId)

            try {
                val shallowUrl = "${FIREBASE_RTDB_URL.trimEnd('/')}/devices.json?shallow=true"
                val conn = (URL(shallowUrl).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 5000
                    readTimeout = 5000
                    requestMethod = "GET"
                }
                if (conn.responseCode == 200) {
                    val body = conn.inputStream.bufferedReader().use { it.readText() }
                    if (body.isNotBlank() && body.trim() != "null") {
                        val json = JSONObject(body)
                        val keys = json.keys()
                        while (keys.hasNext()) {
                            val k = keys.next()
                            if (k.isNotBlank()) discoveredIds.add(k)
                        }
                    }
                }
                conn.disconnect()
            } catch (e: Exception) {
                Log.d(TAG, "Shallow device discovery fallback to self/cache: ${e.message}")
            }

            withContext(Dispatchers.Main) {
                attachDiscoveryListeners(appContext, discoveredIds)
            }
        }
    }

    private fun attachDiscoveryListeners(context: Context, deviceIds: Set<String>) {
        val devicesRef = database?.getReference("devices") ?: return

        // Clear previous discovery listeners
        discoveryListeners.forEach { (ref, listener) ->
            ref.removeEventListener(listener)
        }
        discoveryListeners.clear()

        for (devId in deviceIds) {
            val infoRef = devicesRef.child(devId).child("info")
            val infoListener = object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    discoveredInfoMap[devId] = snapshot
                    rebuildPeerDevicesList(context)
                }
                override fun onCancelled(error: DatabaseError) {}
            }
            infoRef.addValueEventListener(infoListener)
            discoveryListeners.add(infoRef to infoListener)

            val presenceRef = devicesRef.child(devId).child("presence")
            val presenceListener = object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    discoveredPresenceMap[devId] = snapshot
                    rebuildPeerDevicesList(context)
                }
                override fun onCancelled(error: DatabaseError) {}
            }
            presenceRef.addValueEventListener(presenceListener)
            discoveryListeners.add(presenceRef to presenceListener)
        }

        // Immediately choose an initial target device if none is bound yet
        val selfId = getDeviceId(context)
        val selfRole = getDeviceRole(context)
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val savedTarget = prefs.getString(KEY_TARGET_DEVICE_ID, null)?.takeIf { deviceIds.contains(it) }

        val initialTarget = if (selfRole == DeviceRole.WIFI_TABLET) {
            savedTarget?.takeIf { it != selfId }
                ?: deviceIds.firstOrNull { it != selfId && it.startsWith("android_") }
                ?: deviceIds.firstOrNull { it != selfId }
                ?: selfId
        } else {
            savedTarget ?: selfId
        }

        if (_activeTargetDeviceId.value == null || !deviceIds.contains(_activeTargetDeviceId.value)) {
            selectTargetDevice(context, initialTarget)
        }
    }

    private fun rebuildPeerDevicesList(context: Context) {
        val selfId = getDeviceId(context)
        val selfRole = getDeviceRole(context)
        val now = System.currentTimeMillis()
        val allIds = (discoveredInfoMap.keys + discoveredPresenceMap.keys + setOf(selfId)).distinct()

        val list = allIds.map { devId ->
            val infoSnap = discoveredInfoMap[devId]
            val presSnap = discoveredPresenceMap[devId]

            val roleStr = presSnap?.child("deviceRole")?.getValue(String::class.java)
                ?: infoSnap?.child("deviceRole")?.getValue(String::class.java)
                ?: if (devId.startsWith("tablet_")) DeviceRole.WIFI_TABLET.name else DeviceRole.PRIMARY_PHONE.name
            val role = try {
                DeviceRole.valueOf(roleStr)
            } catch (_: Exception) {
                if (devId.startsWith("tablet_")) DeviceRole.WIFI_TABLET else DeviceRole.PRIMARY_PHONE
            }

            val manufacturer = infoSnap?.child("manufacturer")?.getValue(String::class.java) ?: ""
            val model = infoSnap?.child("model")?.getValue(String::class.java) ?: ""
            val fallbackName = listOf(manufacturer, model).filter { it.isNotBlank() }.joinToString(" ").ifBlank { devId }
            val name = presSnap?.child("deviceName")?.getValue(String::class.java) ?: fallbackName

            val lastHb = readLong(presSnap?.child("lastHeartbeat")?.value)
                ?: readLong(infoSnap?.child("lastSeen")?.value)
                ?: 0L
            val explicitOnline = presSnap?.child("online")?.getValue(Boolean::class.java) ?: false
            val isFresh = lastHb > 0L && (now - lastHb < 180_000L)
            val isOnline = explicitOnline || isFresh || devId == selfId

            val pagLimit = readLong(presSnap?.child("paginationLimit")?.value)?.toInt()
                ?: readLong(infoSnap?.child("paginationLimit")?.value)?.toInt()
                ?: 30

            PeerDeviceInfo(
                deviceId = devId,
                deviceName = name,
                deviceRole = role,
                online = isOnline,
                lastHeartbeat = lastHb,
                paginationLimit = pagLimit
            )
        }.sortedWith(
            compareByDescending<PeerDeviceInfo> { it.deviceRole == DeviceRole.PRIMARY_PHONE }
                .thenByDescending { it.online }
                .thenByDescending { it.lastHeartbeat }
        )

        _peerDevices.value = list

        // If on tablet and currently pointing at self only because no phone had loaded yet, auto-switch once phone appears
        val currentTarget = _activeTargetDeviceId.value
        if (selfRole == DeviceRole.WIFI_TABLET && (currentTarget == null || currentTarget == selfId)) {
            val bestPhone = list.firstOrNull { it.deviceId != selfId && it.deviceRole == DeviceRole.PRIMARY_PHONE }
                ?: list.firstOrNull { it.deviceId != selfId }
            if (bestPhone != null) {
                selectTargetDevice(context, bestPhone.deviceId)
            }
        }
    }

    fun selectTargetDevice(context: Context, targetDeviceId: String) {
        if (targetDeviceId.isBlank()) return
        if (_activeTargetDeviceId.value == targetDeviceId && activeTargetThreadsPair != null) return

        val appContext = context.applicationContext
        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_TARGET_DEVICE_ID, targetDeviceId)
            .apply()

        activePrimaryPhoneId = targetDeviceId
        _activeTargetDeviceId.value = targetDeviceId

        // Detach prior target listeners cleanly
        activeTargetThreadsPair?.let { (ref, l) -> ref.removeEventListener(l) }
        activeTargetNotifsPair?.let { (ref, l) -> ref.removeEventListener(l) }
        activeTargetContactsPair?.let { (ref, l) -> ref.removeEventListener(l) }
        activeTargetClipboardPair?.let { (ref, l) -> ref.removeEventListener(l) }

        val deviceRef = database?.getReference("devices")?.child(targetDeviceId) ?: return

        // 1. Live SMS / Group / RCS threads listener
        val threadsRef = deviceRef.child("sms_threads")
        val threadsListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val parsed = mutableListOf<SmsConversationThread>()
                for (child in snapshot.children) {
                    parseThreadSnapshot(child)?.let { parsed.add(it) }
                }
                val mergedWithOptimistic = mergeOptimisticMessages(parsed)
                _syncedThreads.value = mergedWithOptimistic.sortedByDescending { it.lastTimestamp }
                _isSyncingFromPeer.value = false
                Log.d(TAG, "Dashboard updated ${mergedWithOptimistic.size} synced threads from $targetDeviceId")
            }

            override fun onCancelled(error: DatabaseError) {
                _isSyncingFromPeer.value = false
                Log.w(TAG, "Target threads listener cancelled: ${error.message}")
            }
        }
        threadsRef.addValueEventListener(threadsListener)
        activeTargetThreadsPair = threadsRef to threadsListener

        // 2. Live mirrored notifications listener
        val notifsRef = deviceRef.child("outbound_notifications")
        val notifsListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val list = mutableListOf<NotificationPayload>()
                for (child in snapshot.children) {
                    parseNotificationSnapshot(child)?.let { list.add(it) }
                }
                _syncedNotifications.value = list.sortedByDescending { it.timestamp }
            }

            override fun onCancelled(error: DatabaseError) {
                Log.w(TAG, "Target notifications listener cancelled: ${error.message}")
            }
        }
        notifsRef.addValueEventListener(notifsListener)
        activeTargetNotifsPair = notifsRef to notifsListener

        // 3. Contacts listener for autocomplete & recipient resolution
        val contactsRef = deviceRef.child("contacts")
        val contactsListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val contacts = mutableListOf<DeviceContactItem>()
                for (child in snapshot.children) {
                    val id = child.child("id").getValue(String::class.java) ?: child.key ?: ""
                    val name = child.child("name").getValue(String::class.java) ?: ""
                    val phone = child.child("phone").getValue(String::class.java) ?: ""
                    val clean = child.child("cleanDigits").getValue(String::class.java)
                        ?: phone.replace(Regex("\\D"), "")
                    if (name.isNotBlank() || phone.isNotBlank()) {
                        contacts.add(
                            DeviceContactItem(
                                id = id,
                                name = name.ifBlank { phone },
                                phone = phone,
                                cleanDigits = clean
                            )
                        )
                    }
                }
                _syncedContacts.value = contacts.sortedBy { it.name.lowercase() }
            }

            override fun onCancelled(error: DatabaseError) {}
        }
        contactsRef.addValueEventListener(contactsListener)
        activeTargetContactsPair = contactsRef to contactsListener

        // 4. Shared Clipboard listener
        val clipRef = deviceRef.child("clipboard")
        val clipListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val text = snapshot.child("text").getValue(String::class.java) ?: ""
                val source = snapshot.child("source").getValue(String::class.java) ?: ""
                val ts = readLong(snapshot.child("timestamp").value) ?: 0L
                _syncedClipboard.value = if (text.isNotBlank()) {
                    ClipboardItem(text = text, source = source, timestamp = ts)
                } else {
                    null
                }
            }

            override fun onCancelled(error: DatabaseError) {}
        }
        clipRef.addValueEventListener(clipListener)
        activeTargetClipboardPair = clipRef to clipListener

        Log.i(TAG, "Dashboard now synchronized with target device: $targetDeviceId")
    }

    private fun mergeOptimisticMessages(serverThreads: List<SmsConversationThread>): List<SmsConversationThread> {
        val now = System.currentTimeMillis()
        if (optimisticMessagesByThread.isEmpty()) return serverThreads

        val mutableThreads = serverThreads.toMutableList()
        for ((threadId, pendingList) in optimisticMessagesByThread) {
            pendingList.removeAll { now - it.timestamp > 25_000L }
            if (pendingList.isEmpty()) {
                optimisticMessagesByThread.remove(threadId)
                continue
            }
            val idx = mutableThreads.indexOfFirst { it.threadId == threadId }
            if (idx >= 0) {
                val t = mutableThreads[idx]
                val mergedMap = t.messages.toMutableMap()
                for (optMsg in pendingList) {
                    val alreadySynced = mergedMap.values.any { m ->
                        val map = m as? Map<*, *> ?: return@any false
                        val body = map["body"]?.toString() ?: ""
                        val isIncoming = (map["isIncoming"] as? Boolean) ?: true
                        val ts = readLong(map["timestamp"]) ?: 0L
                        !isIncoming && body == optMsg.body && kotlin.math.abs(ts - optMsg.timestamp) < 20_000L
                    }
                    if (!alreadySynced) {
                        mergedMap[optMsg.id] = optMsg.toMap()
                    }
                }
                val latestOpt = pendingList.maxByOrNull { it.timestamp }
                mutableThreads[idx] = t.copy(
                    messages = mergedMap,
                    snippet = latestOpt?.body?.takeIf { (latestOpt.timestamp) > t.lastTimestamp } ?: t.snippet,
                    lastTimestamp = maxOf(t.lastTimestamp, latestOpt?.timestamp ?: 0L)
                )
            }
        }
        return mutableThreads
    }

    /**
     * Parses a Firebase `sms_threads/{id}` child defensively against String vs Long types
     * and Map vs sparse List storage for `messages` and `recipients`.
     */
    fun parseThreadSnapshot(snapshot: DataSnapshot): SmsConversationThread? {
        val keyId = snapshot.key?.toLongOrNull() ?: 0L
        val threadId = readLong(snapshot.child("threadId").value) ?: keyId
        if (threadId <= 0L) return null

        val rawAddress = snapshot.child("address").getValue(String::class.java) ?: ""
        val contactNameRaw = RcsConversationManager.normalizeConversationTitle(
            snapshot.child("contactName").getValue(String::class.java)
        )
        val groupTitleRaw = RcsConversationManager.normalizeConversationTitle(
            snapshot.child("groupTitle").getValue(String::class.java)
        )
        val snippet = snapshot.child("snippet").getValue(String::class.java) ?: ""
        val lastTimestamp = readLong(snapshot.child("lastTimestamp").value) ?: 0L
        val unreadCount = readLong(snapshot.child("unreadCount").value)?.toInt() ?: 0
        val isRcs = snapshot.child("isRcs").getValue(Boolean::class.java) ?: false
        val isEncrypted = snapshot.child("isEncrypted").getValue(Boolean::class.java) ?: false
        val protocol = snapshot.child("protocol").getValue(String::class.java)
            ?: if (isEncrypted) "E2EE_RCS" else if (isRcs) "RCS" else "SMS"
        val isTyping = snapshot.child("isTyping").getValue(Boolean::class.java) ?: false
        val typingSender = snapshot.child("typingSender").getValue(String::class.java)?.takeIf { it.isNotBlank() }

        // Parse recipients defensively (List or Map)
        val recipientsList = mutableListOf<Map<String, String>>()
        val recipsSnap = snapshot.child("recipients")
        if (recipsSnap.exists()) {
            for (child in recipsSnap.children) {
                val map = child.value as? Map<*, *>
                if (map != null) {
                    val phone = map["phone"]?.toString() ?: ""
                    val name = map["name"]?.toString() ?: phone
                    val cleanDigits = map["cleanDigits"]?.toString() ?: phone.replace(Regex("\\D"), "")
                    // Filter out legacy corrupted 60-digit lookup strings if a name is present
                    val cleanPhone = if (phone.replace(Regex("\\D"), "").length > 15 && !phone.contains(",")) {
                        contactNameRaw ?: ""
                    } else phone
                    recipientsList.add(
                        mapOf(
                            "phone" to cleanPhone,
                            "name" to (if (name.replace(Regex("\\D"), "").length > 15) (contactNameRaw ?: "Contact") else name),
                            "cleanDigits" to if (cleanDigits.length > 15) "" else cleanDigits
                        )
                    )
                } else {
                    val strVal = child.getValue(String::class.java)
                    if (!strVal.isNullOrBlank()) {
                        recipientsList.add(
                            mapOf(
                                "phone" to strVal,
                                "name" to strVal,
                                "cleanDigits" to strVal.replace(Regex("\\D"), "")
                            )
                        )
                    }
                }
            }
        }

        val isGroup = (snapshot.child("isGroup").getValue(Boolean::class.java) ?: false) ||
            recipientsList.size > 1 ||
            rawAddress.contains(",")

        // Parse messages defensively (Map or sparse ArrayList)
        val messagesMap = mutableMapOf<String, Any?>()
        val msgsSnap = snapshot.child("messages")
        if (msgsSnap.exists()) {
            for (msgChild in msgsSnap.children) {
                val msgKey = msgChild.key ?: continue
                val rawMap = msgChild.value as? Map<*, *> ?: continue
                val cleanMsg = mutableMapOf<String, Any?>()
                rawMap.forEach { (k, v) ->
                    if (k != null) cleanMsg[k.toString()] = v
                }
                if (!cleanMsg.containsKey("id")) cleanMsg["id"] = msgKey
                messagesMap[msgKey] = cleanMsg
            }
        }

        // Sanitize corrupted lookup-URI phone address for UI presentation
        val sanitizedAddress = if (rawAddress.replace(Regex("\\D"), "").length > 20 && !rawAddress.contains(",")) {
            contactNameRaw ?: groupTitleRaw ?: "RCS Thread"
        } else {
            rawAddress
        }

        return SmsConversationThread(
            threadId = threadId,
            address = sanitizedAddress,
            contactName = contactNameRaw,
            snippet = snippet,
            lastTimestamp = lastTimestamp,
            unreadCount = unreadCount,
            isGroup = isGroup,
            groupTitle = groupTitleRaw,
            recipients = recipientsList,
            messages = messagesMap,
            isRcs = isRcs,
            isEncrypted = isEncrypted,
            protocol = protocol,
            isTyping = isTyping,
            typingSender = typingSender
        )
    }

    /**
     * Converts a thread's `messages` Map into a typed chronologically-sorted list of [SmsMessageItem].
     */
    fun extractSortedMessages(thread: SmsConversationThread): List<SmsMessageItem> {
        return thread.messages.entries.mapNotNull { (key, value) ->
            val map = value as? Map<*, *> ?: return@mapNotNull null
            val ts = readLong(map["timestamp"]) ?: 0L
            val isRcs = (map["isRcs"] as? Boolean) ?: thread.isRcs
            val isEncrypted = (map["isEncrypted"] as? Boolean) ?: thread.isEncrypted
            val protocol = map["protocol"]?.toString()?.takeIf { it.isNotBlank() }
                ?: if (isEncrypted) "E2EE_RCS" else if (isRcs) "RCS" else "SMS"
            SmsMessageItem(
                id = map["id"]?.toString() ?: key,
                threadId = readLong(map["threadId"]) ?: thread.threadId,
                address = map["address"]?.toString() ?: "",
                contactName = map["contactName"]?.toString()?.takeIf { it.isNotBlank() },
                body = map["body"]?.toString() ?: "",
                timestamp = ts,
                isIncoming = (map["isIncoming"] as? Boolean) ?: true,
                isRead = (map["isRead"] as? Boolean) ?: true,
                attachmentUrl = map["attachmentUrl"]?.toString()?.takeIf { it.isNotBlank() },
                attachmentMime = map["attachmentMime"]?.toString()?.takeIf { it.isNotBlank() },
                attachmentData = map["attachmentData"]?.toString()?.takeIf { it.isNotBlank() },
                isRcs = isRcs,
                isEncrypted = isEncrypted,
                protocol = protocol,
                status = map["status"]?.toString()?.takeIf { it.isNotBlank() }
            )
        }.sortedBy { it.timestamp }
    }

    private fun readLong(value: Any?): Long? {
        return when (value) {
            is Number -> value.toLong()
            is String -> value.toLongOrNull()
            else -> null
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

    private val rcsThreadsCache = ConcurrentHashMap<String, SmsConversationThread>()

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
     * Enforces strict rolling retention (max 30 items) to prevent quota exhaustion.
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
                    pruneOutboundNotifications(deviceId, maxKeep = 30)
                }
                .addOnFailureListener { err ->
                    Log.e(TAG, "Failed pushing notification to Firebase: ${err.message}", err)
                }
        }
    }

    private fun pruneOutboundNotifications(deviceId: String, maxKeep: Int = 30) {
        val notifsRef = database?.getReference("devices")?.child(deviceId)?.child("outbound_notifications") ?: return
        notifsRef.addListenerForSingleValueEvent(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (snapshot.childrenCount > maxKeep) {
                    val children = snapshot.children.toList()
                    val toRemoveCount = children.size - maxKeep
                    for (i in 0 until toRemoveCount) {
                        children[i].ref.removeValue()
                    }
                    Log.d(TAG, "Pruned $toRemoveCount old notifications from Firebase (retained $maxKeep).")
                }
            }
            override fun onCancelled(error: DatabaseError) {}
        })
    }

    /**
     * Pushes an intercepted incoming SMS up to Firebase.
     * Enforces rolling retention (max 15 items).
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
                    pruneOutboundSms(deviceId, maxKeep = 15)
                }
                .addOnFailureListener { err ->
                    Log.e(TAG, "Failed syncing SMS to Firebase: ${err.message}", err)
                }
        }
    }

    private fun pruneOutboundSms(deviceId: String, maxKeep: Int = 15) {
        val smsRef = database?.getReference("devices")?.child(deviceId)?.child("outbound_sms") ?: return
        smsRef.addListenerForSingleValueEvent(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (snapshot.childrenCount > maxKeep) {
                    val children = snapshot.children.toList()
                    val toRemoveCount = children.size - maxKeep
                    for (i in 0 until toRemoveCount) {
                        children[i].ref.removeValue()
                    }
                    Log.d(TAG, "Pruned $toRemoveCount old outbound SMS items from Firebase (retained $maxKeep).")
                }
            }
            override fun onCancelled(error: DatabaseError) {}
        })
    }

    /**
     * Reads SMS & MMS conversations with scalable pagination (30, 50, 100 threads)
     * and syncs message histories to Firebase Realtime Database.
     * Preserves active RCS threads and merges without wiping them out.
     */
    fun syncAllSmsThreads(
        context: Context,
        limit: Int = currentPaginationLimit,
        onComplete: ((Int) -> Unit)? = null
    ) {
        setPaginationLimit(context, limit)
        scope.launch {
            try {
                if (!isInitialized) initialize(context)
                val deviceId = getDeviceId(context)
                val threads = SmsRepository.queryConversationThreads(context, maxThreads = limit)
                Log.i(TAG, "Queried ${threads.size} SMS conversation threads (limit: $limit) for sync (including group threads).")

                val threadsRef = database?.getReference("devices")?.child(deviceId)?.child("sms_threads")
                if (threadsRef == null) {
                    withContext(Dispatchers.Main) { onComplete?.invoke(threads.size) }
                    return@launch
                }

                val threadsMap = mutableMapOf<String, Any?>()
                for (thread in threads) {
                    threadsMap[thread.threadId.toString()] = thread.toMap()
                }

                // Preserve all active RCS threads (e.g. family group chats) so they are never wiped out
                for ((rcsKey, rcsThread) in rcsThreadsCache) {
                    threadsMap[rcsKey] = rcsThread.toMap()
                }

                threadsRef.updateChildren(threadsMap)
                    .addOnSuccessListener {
                        Log.i(TAG, "Successfully synced ${threads.size} SMS threads (limit $limit) + ${rcsThreadsCache.size} RCS threads to Firebase.")
                        touchHeartbeat(context)
                        scope.launch(Dispatchers.Main) { onComplete?.invoke(threads.size + rcsThreadsCache.size) }
                    }
                    .addOnFailureListener { err ->
                        Log.e(TAG, "Failed bulk updating SMS threads to Firebase: ${err.message}. Writing individually...", err)
                        scope.launch(Dispatchers.IO) {
                            var individualCount = 0
                            for (thread in threads) {
                                try {
                                    threadsRef.child(thread.threadId.toString()).setValue(thread.toMap())
                                    individualCount++
                                } catch (_: Exception) {}
                            }
                            for ((rcsKey, rcsThread) in rcsThreadsCache) {
                                try {
                                    threadsRef.child(rcsKey).setValue(rcsThread.toMap())
                                    individualCount++
                                } catch (_: Exception) {}
                            }
                            touchHeartbeat(context)
                            withContext(Dispatchers.Main) { onComplete?.invoke(individualCount) }
                        }
                    }
            } catch (t: Throwable) {
                Log.e(TAG, "Error in syncAllSmsThreads: ${t.message}", t)
                withContext(Dispatchers.Main) { onComplete?.invoke(0) }
            }
        }
    }

    /**
     * Directly writes a specific conversation thread object to Firebase Realtime Database.
     * Guarantees instantaneous delivery without querying the SQLite Telephony provider.
     */
    fun syncThreadDirectly(context: Context, thread: SmsConversationThread, onComplete: (() -> Unit)? = null) {
        if (thread.threadId <= 0) return
        rcsThreadsCache[thread.threadId.toString()] = thread
        scope.launch {
            if (!isInitialized) initialize(context)
            val deviceId = getDeviceId(context)
            val threadRef = database?.getReference("devices")
                ?.child(deviceId)
                ?.child("sms_threads")
                ?.child(thread.threadId.toString()) ?: return@launch

            threadRef.setValue(thread.toMap())
                .addOnSuccessListener {
                    Log.i(TAG, "Directly synced thread ${thread.threadId} ('${thread.contactName}', isGroup=${thread.isGroup}) to Firebase.")
                    touchHeartbeat(context)
                    onComplete?.invoke()
                }
                .addOnFailureListener { err ->
                    Log.w(TAG, "Failed directly syncing thread ${thread.threadId}: ${err.message}")
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

            // 1. Check in-memory RCS conversation cache first for instant sync
            val rcsThread = RcsConversationManager.getRcsThread(threadId)
            if (rcsThread != null) {
                val threadRef = database?.getReference("devices")
                    ?.child(deviceId)
                    ?.child("sms_threads")
                    ?.child(threadId.toString()) ?: return@launch

                threadRef.setValue(rcsThread.toMap())
                    .addOnSuccessListener {
                        Log.i(TAG, "Successfully synced cached RCS thread $threadId to Firebase.")
                        touchHeartbeat(context)
                        onComplete?.invoke()
                    }
                return@launch
            }

            // 2. Query Telephony threads with high limit (100) to find the thread
            val threads = SmsRepository.queryConversationThreads(context, maxThreads = 100)
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
                syncAllSmsThreads(context, limit = currentPaginationLimit)
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
                withContext(Dispatchers.Main) { onComplete?.invoke(0) }
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
                    scope.launch(Dispatchers.Main) { onComplete?.invoke(contacts.size) }
                }
                .addOnFailureListener { err ->
                    Log.w(TAG, "Failed syncing device contacts: ${err.message}")
                    scope.launch(Dispatchers.Main) { onComplete?.invoke(0) }
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

        try {
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
                    val contactNameFallback = snapshot.child("contactName").getValue(String::class.java) ?: ""
                    val message = snapshot.child("message").getValue(String::class.java) ?: ""
                    val threadIdVal = readLong(snapshot.child("threadId").value) ?: 0L
                    val isGroup = snapshot.child("isGroup").getValue(Boolean::class.java) ?: false

                    val attachmentData = snapshot.child("attachmentData").getValue(String::class.java)?.takeIf { it.isNotBlank() }
                        ?: when (val attVal = snapshot.child("attachment").value) {
                            is String -> attVal.takeIf { it.isNotBlank() }
                            is Map<*, *> -> (attVal["base64"] ?: attVal["data"]) as? String
                            else -> null
                        }

                    // 1. Check if thread has an active RCS session with RemoteInput capability
                    val rcsKey = "rcs_thread_$threadIdVal"
                    if (threadIdVal > 0 && NotificationReplyRegistry.canReply(rcsKey)) {
                        val replyResult = NotificationReplyRegistry.executeRemoteReply(context, rcsKey, message)
                        if (replyResult is ReplyResult.Success) {
                            updateCommandStatus(
                                snapshot.ref,
                                "completed",
                                "Dispatched natively via RCS session (${replyResult.packageName})"
                            )
                            recordSentMessageInThread(
                                context = context,
                                threadIdVal = threadIdVal,
                                recipient = recipient.ifBlank { contactNameFallback },
                                message = message,
                                isGroup = isGroup,
                                attachmentData = attachmentData
                            )
                            syncSingleThread(context, threadIdVal)
                            return
                        }
                    }

                    // Extract recipient list for group or 1-on-1 SMS
                    val recipientsSnap = snapshot.child("recipients")
                    val rawRecipientsList = mutableListOf<String>()
                    if (recipientsSnap.exists()) {
                        for (child in recipientsSnap.children) {
                            val p = when (val v = child.value) {
                                is String -> v
                                is Map<*, *> -> (v["phone"] ?: v["name"])?.toString() ?: ""
                                else -> ""
                            }
                            if (p.isNotBlank()) rawRecipientsList.add(p)
                        }
                    }
                    if (rawRecipientsList.isEmpty() && recipient.isNotBlank()) {
                        rawRecipientsList.addAll(recipient.split(",", ";").map { it.trim() }.filter { it.isNotBlank() })
                    }

                    val resolvedTargets = resolveDispatchTargets(context, rawRecipientsList, contactNameFallback)

                    if (isGroup || resolvedTargets.size > 1) {
                        var successCount = 0
                        val failures = mutableListOf<String>()

                        for (targetPhone in resolvedTargets) {
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
                                "Group SMS broadcasted to $successCount of ${resolvedTargets.size} recipients"
                            )
                            recordSentMessageInThread(
                                context = context,
                                threadIdVal = threadIdVal,
                                recipient = recipient,
                                message = message,
                                isGroup = true,
                                recipientsList = resolvedTargets,
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
                        val singleRecipient = resolvedTargets.firstOrNull() ?: recipient
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
                    val limit = (readLong(snapshot.child("limit").value)?.toInt() ?: currentPaginationLimit)
                        .coerceIn(10, 200)
                    syncAllSmsThreads(context, limit = limit) { count ->
                        updateCommandStatus(snapshot.ref, "completed", "Synced $count SMS threads (limit $limit) to Firebase")
                    }
                }

                "SYNC_CONTACTS" -> {
                    syncDeviceContacts(context) { count ->
                        updateCommandStatus(snapshot.ref, "completed", "Synced $count device contacts")
                    }
                }

                "PING" -> {
                    updateCommandStatus(snapshot.ref, "completed", "Pong from Android device")
                }

                else -> {
                    updateCommandStatus(snapshot.ref, "failed", "Unknown command type: $type")
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Exception while processing command $commandId ($type): ${t.message}", t)
            updateCommandStatus(snapshot.ref, "failed", t.message ?: "Command processing error")
        }
    }

    private fun resolveDispatchTargets(
        context: Context,
        rawList: List<String>,
        contactNameFallback: String
    ): List<String> {
        val out = mutableListOf<String>()
        for (raw in rawList) {
            val trimmed = raw.trim()
            if (trimmed.isBlank()) continue
            if (SmsRepository.isValidPhoneAddress(trimmed)) {
                out.add(SmsRepository.normalizePhoneNumber(trimmed).ifBlank { trimmed })
            } else {
                // Attempt contact name resolution if text name or corrupted digits
                val byTarget = if (trimmed.any { it.isLetter() }) {
                    SmsRepository.resolvePhoneNumberForContactName(context, trimmed)
                } else null
                val byFallback = if (byTarget == null && contactNameFallback.isNotBlank()) {
                    SmsRepository.resolvePhoneNumberForContactName(context, contactNameFallback)
                } else null
                out.add(byTarget ?: byFallback ?: trimmed)
            }
        }
        if (out.isEmpty() && contactNameFallback.isNotBlank()) {
            val resolved = SmsRepository.resolvePhoneNumberForContactName(context, contactNameFallback)
            if (!resolved.isNullOrBlank()) out.add(resolved)
        }
        return out.distinct()
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
            "contactName" to "Me",
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

        // Preserve message in RcsConversationManager cache if this is an RCS thread
        val cachedRcs = RcsConversationManager.getRcsThread(threadIdVal)
        if (cachedRcs != null) {
            val updatedMsgs = cachedRcs.messages.toMutableMap()
            updatedMsgs[msgId] = msgMap + ("timestamp" to System.currentTimeMillis())
            RcsConversationManager.saveOrUpdateThread(
                cachedRcs.copy(
                    snippet = if (message.isNotBlank()) message else "[Photo]",
                    lastTimestamp = System.currentTimeMillis(),
                    messages = updatedMsgs
                )
            )
        }

        // Asynchronously refresh telephony threads when relevant
        if (threadIdVal < 8_000_000_000L) {
            syncAllSmsThreads(context, currentPaginationLimit)
        }
    }

    // =========================================================================
    // INTERACTIVE DASHBOARD COMMANDS (Tablet Companion & Phone UI Actions)
    // =========================================================================

    fun sendChatMessage(
        context: Context,
        thread: SmsConversationThread?,
        newRecipientRaw: String?,
        messageBody: String,
        onResult: ((Boolean, String?) -> Unit)? = null
    ) {
        val trimmedBody = messageBody.trim()
        if (trimmedBody.isBlank()) {
            onResult?.invoke(false, "Message cannot be empty.")
            return
        }

        val targetDeviceId = _activeTargetDeviceId.value ?: getDeviceId(context)
        val selfDeviceId = getDeviceId(context)

        val resolvedThreadId: Long
        val recipientString: String
        val recipientsList: List<String>
        val isGroup: Boolean
        val contactName: String

        if (thread != null) {
            resolvedThreadId = thread.threadId
            isGroup = thread.isGroup || thread.recipients.size > 1
            recipientsList = thread.recipients.mapNotNull { r ->
                val p = r["phone"]?.takeIf { SmsRepository.isValidPhoneAddress(it) }
                val n = r["name"]?.takeIf { it.isNotBlank() }
                p ?: n
            }.filter { it.isNotBlank() }.distinct()
            recipientString = if (SmsRepository.isValidPhoneAddress(thread.address) || thread.address.contains(",")) {
                thread.address
            } else {
                recipientsList.firstOrNull() ?: thread.contactName ?: thread.groupTitle ?: ""
            }
            contactName = thread.groupTitle ?: thread.contactName ?: recipientString
        } else {
            val rawInput = newRecipientRaw?.trim() ?: ""
            if (rawInput.isBlank()) {
                onResult?.invoke(false, "Please enter a recipient.")
                return
            }
            val splitRecips = rawInput.split(",", ";").map { it.trim() }.filter { it.isNotBlank() }
            isGroup = splitRecips.size > 1
            recipientsList = splitRecips
            recipientString = rawInput
            resolvedThreadId = 0L
            contactName = rawInput
        }

        // Optimistically add outgoing bubble to UI immediately
        if (resolvedThreadId > 0L) {
            val now = System.currentTimeMillis()
            val optimisticMsg = SmsMessageItem(
                id = "opt_${now}",
                threadId = resolvedThreadId,
                address = recipientString,
                contactName = "Me",
                body = trimmedBody,
                timestamp = now,
                isIncoming = false,
                isRead = true,
                isRcs = thread?.isRcs ?: false,
                isEncrypted = thread?.isEncrypted ?: false,
                protocol = thread?.protocol ?: "SMS",
                status = "sending"
            )
            optimisticMessagesByThread.getOrPut(resolvedThreadId) { mutableListOf() }.add(optimisticMsg)
            _syncedThreads.value = mergeOptimisticMessages(_syncedThreads.value).sortedByDescending { it.lastTimestamp }
        }

        // If user is directly on their Primary Phone targeting itself, execute immediately
        if (targetDeviceId == selfDeviceId && getDeviceRole(context) == DeviceRole.PRIMARY_PHONE) {
            scope.launch {
                val rcsKey = "rcs_thread_$resolvedThreadId"
                if (resolvedThreadId > 0L && NotificationReplyRegistry.canReply(rcsKey)) {
                    val replyRes = NotificationReplyRegistry.executeRemoteReply(context, rcsKey, trimmedBody)
                    if (replyRes is ReplyResult.Success) {
                        recordSentMessageInThread(context, resolvedThreadId, recipientString, trimmedBody, isGroup, recipientsList)
                        withContext(Dispatchers.Main) { onResult?.invoke(true, "Sent via RCS") }
                        return@launch
                    }
                }
                val targets = resolveDispatchTargets(context, recipientsList.ifEmpty { listOf(recipientString) }, contactName)
                var ok = false
                var errMsg: String? = null
                for (t in targets) {
                    when (val res = SmsManagerHelper.sendSms(context, t, trimmedBody)) {
                        is SmsSendResult.Success -> ok = true
                        is SmsSendResult.Failure -> errMsg = res.error
                    }
                }
                if (ok) {
                    recordSentMessageInThread(context, resolvedThreadId, recipientString, trimmedBody, isGroup, targets)
                    withContext(Dispatchers.Main) { onResult?.invoke(true, "Sent") }
                } else {
                    withContext(Dispatchers.Main) { onResult?.invoke(false, errMsg ?: "Failed to send SMS") }
                }
            }
            return
        }

        // Otherwise queue command on the paired Primary Phone
        val commandRef = database?.getReference("devices")?.child(targetDeviceId)?.child("inbound_commands")?.push()
        if (commandRef == null) {
            onResult?.invoke(false, "Firebase not initialized")
            return
        }

        val payload = mapOf(
            "type" to "SEND_SMS",
            "sourceDeviceId" to selfDeviceId,
            "recipient" to recipientString,
            "contactName" to contactName,
            "message" to trimmedBody,
            "isGroup" to isGroup,
            "recipients" to recipientsList,
            "threadId" to resolvedThreadId,
            "isRcs" to (thread?.isRcs ?: false),
            "isEncrypted" to (thread?.isEncrypted ?: false),
            "protocol" to (thread?.protocol ?: "SMS"),
            "timestamp" to ServerValue.TIMESTAMP,
            "status" to "pending"
        )

        commandRef.setValue(payload)
            .addOnSuccessListener {
                Log.i(TAG, "Queued SEND_SMS command to phone $targetDeviceId (threadId=$resolvedThreadId)")
                onResult?.invoke(true, "Sent via phone")
            }
            .addOnFailureListener { err ->
                onResult?.invoke(false, err.message ?: "Failed to queue message")
            }
    }

    fun requestPeerSmsSync(context: Context, limit: Int = currentPaginationLimit, onComplete: ((Boolean, String?) -> Unit)? = null) {
        val targetDeviceId = _activeTargetDeviceId.value ?: getDeviceId(context)
        val selfDeviceId = getDeviceId(context)
        setPaginationLimit(context, limit)
        _isSyncingFromPeer.value = true

        if (targetDeviceId == selfDeviceId && getDeviceRole(context) == DeviceRole.PRIMARY_PHONE) {
            syncAllSmsThreads(context, limit) { count ->
                _isSyncingFromPeer.value = false
                onComplete?.invoke(true, "Synchronized $count threads")
            }
            return
        }

        val commandRef = database?.getReference("devices")?.child(targetDeviceId)?.child("inbound_commands")?.push()
        if (commandRef == null) {
            _isSyncingFromPeer.value = false
            onComplete?.invoke(false, "Firebase unavailable")
            return
        }

        val payload = mapOf(
            "type" to "SYNC_SMS_THREADS",
            "sourceDeviceId" to selfDeviceId,
            "limit" to limit,
            "timestamp" to ServerValue.TIMESTAMP,
            "status" to "pending"
        )

        commandRef.setValue(payload)
            .addOnSuccessListener {
                scope.launch {
                    delay(8000L)
                    _isSyncingFromPeer.value = false
                }
                onComplete?.invoke(true, "Sync requested from phone (limit $limit)")
            }
            .addOnFailureListener { err ->
                _isSyncingFromPeer.value = false
                onComplete?.invoke(false, err.message)
            }
    }

    fun requestPeerContactsSync(context: Context, onComplete: ((Boolean, String?) -> Unit)? = null) {
        val targetDeviceId = _activeTargetDeviceId.value ?: getDeviceId(context)
        val selfDeviceId = getDeviceId(context)

        if (targetDeviceId == selfDeviceId && getDeviceRole(context) == DeviceRole.PRIMARY_PHONE) {
            syncDeviceContacts(context) { count ->
                onComplete?.invoke(true, "Synced $count device contacts to Firebase!")
            }
            return
        }

        val commandRef = database?.getReference("devices")?.child(targetDeviceId)?.child("inbound_commands")?.push()
        if (commandRef == null) {
            onComplete?.invoke(false, "Firebase unavailable")
            return
        }

        val payload = mapOf(
            "type" to "SYNC_CONTACTS",
            "sourceDeviceId" to selfDeviceId,
            "timestamp" to ServerValue.TIMESTAMP,
            "status" to "pending"
        )

        commandRef.setValue(payload)
            .addOnSuccessListener {
                onComplete?.invoke(true, "Requested contacts sync from phone.")
            }
            .addOnFailureListener { err ->
                onComplete?.invoke(false, err.message)
            }
    }

    fun sendRemoteReplyFromDashboard(
        context: Context,
        notificationKey: String,
        replyText: String,
        onComplete: ((Boolean, String?) -> Unit)? = null
    ) {
        val trimmed = replyText.trim()
        if (trimmed.isBlank()) {
            onComplete?.invoke(false, "Reply cannot be empty")
            return
        }
        val targetDeviceId = _activeTargetDeviceId.value ?: getDeviceId(context)
        val selfDeviceId = getDeviceId(context)

        if (targetDeviceId == selfDeviceId && getDeviceRole(context) == DeviceRole.PRIMARY_PHONE) {
            when (val res = NotificationReplyRegistry.executeRemoteReply(context, notificationKey, trimmed)) {
                is ReplyResult.Success -> onComplete?.invoke(true, "Replied via ${res.packageName}")
                is ReplyResult.Failure -> onComplete?.invoke(false, res.error)
            }
            return
        }

        sendTabletRemoteReply(context, targetDeviceId, notificationKey, trimmed) { ok ->
            onComplete?.invoke(ok, if (ok) "Reply queued on phone" else "Failed to send reply")
        }
    }

    fun dismissRemoteNotification(notificationId: String) {
        val targetDeviceId = _activeTargetDeviceId.value ?: return
        _syncedNotifications.value = _syncedNotifications.value.filterNot { it.id == notificationId }
        database?.getReference("devices")
            ?.child(targetDeviceId)
            ?.child("outbound_notifications")
            ?.child(notificationId)
            ?.removeValue()
    }

    fun dismissAppNotifications(appName: String) {
        val targetDeviceId = _activeTargetDeviceId.value ?: return
        val toRemove = _syncedNotifications.value.filter { it.appName == appName }.map { it.id }
        _syncedNotifications.value = _syncedNotifications.value.filterNot { it.appName == appName }
        val notifsRef = database?.getReference("devices")
            ?.child(targetDeviceId)
            ?.child("outbound_notifications") ?: return
        for (id in toRemove) {
            notifsRef.child(id).removeValue()
        }
    }

    fun clearAllRemoteNotifications() {
        val targetDeviceId = _activeTargetDeviceId.value ?: return
        _syncedNotifications.value = emptyList()
        database?.getReference("devices")
            ?.child(targetDeviceId)
            ?.child("outbound_notifications")
            ?.removeValue()
    }

    fun pushClipboardText(context: Context, text: String, onComplete: ((Boolean) -> Unit)? = null) {
        val targetDeviceId = _activeTargetDeviceId.value ?: getDeviceId(context)
        val selfDeviceId = getDeviceId(context)
        val clipPayload = mapOf(
            "text" to text,
            "source" to selfDeviceId,
            "timestamp" to ServerValue.TIMESTAMP
        )
        _syncedClipboard.value = ClipboardItem(text = text, source = selfDeviceId, timestamp = System.currentTimeMillis())

        val targetRef = database?.getReference("devices")?.child(targetDeviceId)?.child("clipboard")
        if (targetRef == null) {
            onComplete?.invoke(false)
            return
        }
        targetRef.setValue(clipPayload)
            .addOnSuccessListener {
                if (targetDeviceId != selfDeviceId) {
                    database?.getReference("devices")?.child(selfDeviceId)?.child("clipboard")?.setValue(clipPayload)
                }
                onComplete?.invoke(true)
            }
            .addOnFailureListener {
                onComplete?.invoke(false)
            }
    }

    // =========================================================================
    // MULTI-DEVICE WI-FI TABLET COMPANION LEGACY API
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
        startPeerDiscoveryAndSync(context)
        val devicesRef = database?.getReference("devices") ?: return

        devicesRef.addListenerForSingleValueEvent(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val currentTabletId = getDeviceId(context)
                for (child in snapshot.children) {
                    val devId = child.key ?: continue
                    if (devId.startsWith("android_") && devId != currentTabletId) {
                        activePrimaryPhoneId = devId
                        Log.i(TAG, "Tablet Companion paired with Primary Phone: $devId")
                        if (onThreadsUpdated != null || onNotificationReceived != null) {
                            attachTabletListeners(devId, onThreadsUpdated, onNotificationReceived)
                        }
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
        phoneId: String,
        onThreadsUpdated: ((Map<String, Any?>) -> Unit)?,
        onNotificationReceived: ((NotificationPayload) -> Unit)?
    ) {
        val phoneRef = database?.getReference("devices")?.child(phoneId) ?: return

        tabletPeerThreadsListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val threadsMap = mutableMapOf<String, Any?>()
                for (child in snapshot.children) {
                    threadsMap[child.key ?: ""] = child.value
                }
                onThreadsUpdated?.invoke(threadsMap)
            }

            override fun onCancelled(error: DatabaseError) {
                Log.w(TAG, "Tablet threads listener cancelled: ${error.message}")
            }
        }
        phoneRef.child("sms_threads").addValueEventListener(tabletPeerThreadsListener as ValueEventListener)

        tabletPeerNotifsListener = object : ChildEventListener {
            override fun onChildAdded(snapshot: DataSnapshot, previousChildName: String?) {
                val notif = parseNotificationSnapshot(snapshot)
                if (notif != null) {
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
        val subText = snapshot.child("subText").getValue(String::class.java)?.takeIf { it.isNotBlank() }
        val appName = snapshot.child("appName").getValue(String::class.java) ?: "App"
        val packageName = snapshot.child("packageName").getValue(String::class.java) ?: ""
        val notificationKey = snapshot.child("notificationKey").getValue(String::class.java) ?: id
        val canReply = snapshot.child("canReply").getValue(Boolean::class.java) ?: false
        val replyActionTitle = snapshot.child("replyActionTitle").getValue(String::class.java)?.takeIf { it.isNotBlank() }
        val isMessagingApp = snapshot.child("isMessagingApp").getValue(Boolean::class.java) ?: false
        val isRcs = snapshot.child("isRcs").getValue(Boolean::class.java) ?: false
        val isEncrypted = snapshot.child("isEncrypted").getValue(Boolean::class.java) ?: false
        val isGroup = snapshot.child("isGroup").getValue(Boolean::class.java) ?: false
        val conversationTitle = snapshot.child("conversationTitle").getValue(String::class.java)?.takeIf { it.isNotBlank() }
        val timestamp = readLong(snapshot.child("timestamp").value) ?: System.currentTimeMillis()

        return NotificationPayload(
            id = id,
            notificationKey = notificationKey,
            packageName = packageName,
            appName = appName,
            title = title,
            text = text,
            subText = subText,
            canReply = canReply,
            replyActionTitle = replyActionTitle,
            isMessagingApp = isMessagingApp,
            isRcs = isRcs,
            isEncrypted = isEncrypted,
            isGroup = isGroup,
            conversationTitle = conversationTitle,
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
        ref.updateChildren(updates).addOnCompleteListener {
            // Automatically clean up executed command node to prevent inbound_commands backlog
            ref.removeValue()
        }
    }

    fun getDeviceId(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        var id = prefs.getString(KEY_DEVICE_ID, null)
        if (id == null) {
            val role = getDeviceRole(context)
            val prefix = if (role == DeviceRole.WIFI_TABLET) "tablet_" else "android_"
            id = prefix + UUID.randomUUID().toString().substring(0, 8)
            prefs.edit().putString(KEY_DEVICE_ID, id).apply()
        }
        return id
    }
}
