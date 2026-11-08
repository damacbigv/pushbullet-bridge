package com.example.pushbridge.util

import android.app.Notification
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.core.app.NotificationCompat
import java.util.concurrent.ConcurrentHashMap

data class CachedReplyAction(
    val notificationKey: String,
    val packageName: String,
    val pendingIntent: PendingIntent,
    val remoteInputs: Array<RemoteInput>,
    val actionTitle: String,
    val resultKeys: List<String>,
    val timestamp: Long = System.currentTimeMillis()
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as CachedReplyAction
        return notificationKey == other.notificationKey
    }

    override fun hashCode(): Int {
        return notificationKey.hashCode()
    }
}

sealed class ReplyResult {
    data class Success(val notificationKey: String, val packageName: String) : ReplyResult()
    data class Failure(val notificationKey: String, val error: String, val throwable: Throwable? = null) : ReplyResult()
}

object NotificationReplyRegistry {
    private const val TAG = "ReplyRegistry"

    // Thread-safe cache of active reply actions
    private val actionCache = ConcurrentHashMap<String, CachedReplyAction>()

    /**
     * Inspects a StatusBarNotification and extracts any RemoteInput reply action.
     * Caches the action in memory so remote clients can execute replies.
     */
    fun extractAndRegisterReplyAction(sbn: StatusBarNotification): CachedReplyAction? {
        val notification = sbn.notification ?: return null
        val notificationKey = sbn.key

        // 1. Check standard notification.actions
        val standardActions = notification.actions
        if (!standardActions.isNullOrEmpty()) {
            for (action in standardActions) {
                val remoteInputs = action.remoteInputs
                if (!remoteInputs.isNullOrEmpty() && action.actionIntent != null) {
                    val resultKeys = remoteInputs.map { it.resultKey }
                    val cached = CachedReplyAction(
                        notificationKey = notificationKey,
                        packageName = sbn.packageName,
                        pendingIntent = action.actionIntent,
                        remoteInputs = remoteInputs,
                        actionTitle = action.title?.toString() ?: "Reply",
                        resultKeys = resultKeys
                    )
                    actionCache[notificationKey] = cached
                    Log.d(TAG, "Cached standard RemoteInput reply action for ${sbn.packageName} (key: $notificationKey)")
                    return cached
                }
            }
        }

        // 2. Check WearableExtender actions (used by some messaging apps)
        try {
            val wearableExtender = NotificationCompat.WearableExtender(notification)
            for (action in wearableExtender.actions) {
                val remoteInputs = action.remoteInputs
                val pendingIntent = action.actionIntent
                if (!remoteInputs.isNullOrEmpty() && pendingIntent != null) {
                    val nativeInputs = remoteInputs.map { compatInput ->
                        RemoteInput.Builder(compatInput.resultKey)
                            .setLabel(compatInput.label)
                            .setChoices(compatInput.choices)
                            .setAllowFreeFormInput(compatInput.allowFreeFormInput)
                            .build()
                    }.toTypedArray()

                    val resultKeys = nativeInputs.map { it.resultKey }
                    val cached = CachedReplyAction(
                        notificationKey = notificationKey,
                        packageName = sbn.packageName,
                        pendingIntent = pendingIntent,
                        remoteInputs = nativeInputs,
                        actionTitle = action.title?.toString() ?: "Reply",
                        resultKeys = resultKeys
                    )
                    actionCache[notificationKey] = cached
                    Log.d(TAG, "Cached wearable RemoteInput reply action for ${sbn.packageName} (key: $notificationKey)")
                    return cached
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed inspecting WearableExtender: ${e.message}")
        }

        return null
    }

    /**
     * Registers or aliases a reply action under a custom key (e.g. rcs_thread_<id>).
     */
    fun registerReplyAction(key: String, action: CachedReplyAction) {
        actionCache[key] = action
    }

    /**
     * Removes cached reply action when notification is dismissed.
     */
    fun unregister(notificationKey: String) {
        actionCache.remove(notificationKey)
    }

    /**
     * Returns true if the notification key has an active RemoteInput action.
     */
    fun canReply(notificationKey: String): Boolean {
        return actionCache.containsKey(notificationKey)
    }

    /**
     * Executes the RemoteInput PendingIntent natively, sending the user's text back to the app.
     */
    fun executeRemoteReply(context: Context, notificationKey: String, replyText: String): ReplyResult {
        val cached = actionCache[notificationKey]
        if (cached == null) {
            Log.w(TAG, "No cached reply action found for key: $notificationKey. Active keys: ${actionCache.keys.toList()}")
            return ReplyResult.Failure(
                notificationKey,
                "Notification is no longer active in status bar or does not support remote replies."
            )
        }

        return try {
            val replyIntent = Intent()
            val bundle = Bundle()

            for (remoteInput in cached.remoteInputs) {
                bundle.putCharSequence(remoteInput.resultKey, replyText)
            }

            // Populate RemoteInput results into the intent
            RemoteInput.addResultsToIntent(cached.remoteInputs, replyIntent, bundle)

            // Indicate free-form human input on Android P+
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                RemoteInput.setResultsSource(replyIntent, RemoteInput.SOURCE_FREE_FORM_INPUT)
            }

            // Dispatch PendingIntent
            cached.pendingIntent.send(context, 0, replyIntent)
            Log.i(TAG, "Successfully fired RemoteInput reply to ${cached.packageName} with text: '$replyText'")

            ReplyResult.Success(notificationKey, cached.packageName)
        } catch (e: PendingIntent.CanceledException) {
            Log.e(TAG, "PendingIntent canceled for $notificationKey", e)
            ReplyResult.Failure(notificationKey, "PendingIntent was canceled by originating application.", e)
        } catch (e: Exception) {
            Log.e(TAG, "Error executing remote reply: ${e.message}", e)
            ReplyResult.Failure(notificationKey, e.message ?: "Unknown error executing remote reply", e)
        }
    }
}
