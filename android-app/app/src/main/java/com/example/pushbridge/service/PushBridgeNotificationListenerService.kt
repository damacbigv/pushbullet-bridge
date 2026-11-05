package com.example.pushbridge.service

import android.app.Notification
import android.content.pm.PackageManager
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.example.pushbridge.data.PushBridgeFirebaseManager
import com.example.pushbridge.data.PushBridgeSyncManager
import com.example.pushbridge.model.NotificationPayload
import com.example.pushbridge.util.NotificationReplyRegistry

class PushBridgeNotificationListenerService : NotificationListenerService() {

    companion object {
        private const val TAG = "PushBridgeNotifListener"

        // Active singleton instance for querying active notifications dynamically
        var currentInstance: PushBridgeNotificationListenerService? = null
            private set

        var isConnected: Boolean = false
            private set

        // Targeted messaging packages (SMS, Chat, Work Collaboration)
        private val TARGET_MESSAGING_PACKAGES = setOf(
            // SMS Apps
            "com.google.android.apps.messaging",
            "com.samsung.android.messaging",
            "com.android.mms",
            "com.android.messaging",
            // Popular Messaging Platforms
            "com.whatsapp",
            "com.whatsapp.w4b",
            "org.telegram.messenger",
            "org.telegram.plus",
            "org.thoughtcrime.securesms", // Signal
            "com.facebook.orca",          // Facebook Messenger
            "com.facebook.mlite",
            "com.Slack",                  // Slack
            "com.discord",                // Discord
            "com.skype.raider",           // Skype
            "com.microsoft.teams",        // Teams
            "com.viber.voip",             // Viber
            "com.google.android.talk",    // Google Chat
            "jp.naver.line.android",      // Line
            "com.tencent.mm"              // WeChat
        )
    }

    override fun onCreate() {
        super.onCreate()
        currentInstance = this
        PushBridgeFirebaseManager.initialize(applicationContext)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (currentInstance == this) {
            currentInstance = null
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        currentInstance = this
        isConnected = true
        Log.i(TAG, "PushBridge Notification Listener connected and active.")
        PushBridgeFirebaseManager.registerDeviceState(applicationContext)
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        isConnected = false
        Log.i(TAG, "PushBridge Notification Listener disconnected.")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        if (sbn == null) return

        val pkgName = sbn.packageName

        // Never intercept notifications originating from PushBridge itself to avoid feedback loops
        if (pkgName == packageName) return

        val notification = sbn.notification ?: return
        val extras = notification.extras ?: return

        // Filter out persistent background services and media controllers unless they are actual messages
        val isOngoing = (notification.flags and Notification.FLAG_ONGOING_EVENT) != 0
        val isForegroundService = (notification.flags and Notification.FLAG_FOREGROUND_SERVICE) != 0
        val isMessagingApp = TARGET_MESSAGING_PACKAGES.contains(pkgName)

        // Extract Title
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
            ?: extras.getCharSequence(Notification.EXTRA_TITLE_BIG)?.toString()
            ?: ""

        // Extract Text body
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
            ?: extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
            ?: ""

        val subText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()

        // Ignore empty or persistent ongoing indicators for non-messaging apps
        if (title.isBlank() && text.isBlank()) return
        if ((isOngoing || isForegroundService) && !isMessagingApp) {
            // Skip ongoing non-messaging alerts like download progress or media players
            return
        }

        // Get friendly application label
        val appName = try {
            val pm = packageManager
            val ai = pm.getApplicationInfo(pkgName, 0)
            pm.getApplicationLabel(ai).toString()
        } catch (_: PackageManager.NameNotFoundException) {
            pkgName
        }

        // Critically: inspect and extract RemoteInput reply actions
        val replyAction = NotificationReplyRegistry.extractAndRegisterReplyAction(sbn)
        val canReply = replyAction != null
        val replyTitle = replyAction?.actionTitle

        // Unique ID safe for Firebase paths
        val safeId = "notif_${sbn.postTime}_${Math.abs(sbn.key.hashCode())}"

        val payload = NotificationPayload(
            id = safeId,
            notificationKey = sbn.key,
            packageName = pkgName,
            appName = appName,
            title = title,
            text = text,
            subText = subText,
            canReply = canReply,
            replyActionTitle = replyTitle,
            isMessagingApp = isMessagingApp,
            timestamp = sbn.postTime
        )

        Log.i(
            TAG,
            "Intercepted [${if (isMessagingApp) "MESSAGING" else "ALERT"}]: '$appName' - '$title' (Can Reply: $canReply)"
        )

        // 1. Instantly push to Firebase Realtime Database
        PushBridgeFirebaseManager.pushNotification(applicationContext, payload)

        // 2. Dispatch to Fallback Express Relay Service
        PushBridgeSyncManager.syncNotification(applicationContext, payload)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        super.onNotificationRemoved(sbn)
        if (sbn == null) return

        NotificationReplyRegistry.unregister(sbn.key)
        Log.d(TAG, "Notification dismissed from status bar: ${sbn.packageName} (key: ${sbn.key})")
    }
}
