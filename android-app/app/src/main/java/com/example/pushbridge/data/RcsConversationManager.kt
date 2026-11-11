package com.example.pushbridge.data

import android.app.Notification
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.provider.Telephony
import android.service.notification.StatusBarNotification
import android.util.Base64
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import com.example.pushbridge.model.SmsConversationThread
import com.example.pushbridge.model.SmsMessageItem
import com.example.pushbridge.model.SmsPayload
import com.example.pushbridge.util.NotificationReplyRegistry
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentHashMap

data class RcsInterceptResult(
    val isRcs: Boolean,
    val thread: SmsConversationThread,
    val latestMessage: SmsMessageItem,
    val smsPayload: SmsPayload
)

/**
 * Manages Rich Communication Services (RCS) data packets, end-to-end encrypted (E2EE) chats,
 * typing indicators, read receipts, and rich media descriptors intercepted from active notifications.
 */
object RcsConversationManager {
    private const val TAG = "RcsConversationManager"

    // Recognized packages implementing modern RCS / Universal Profile / E2EE Messaging
    val RCS_PACKAGES = setOf(
        "com.google.android.apps.messaging", // Google Messages (Jibe RCS & E2EE)
        "com.samsung.android.messaging",     // Samsung Messages (RCS)
        "com.verizon.messaging.vzmsgs",      // Verizon Message+
        "com.android.mms"
    )

    // In-memory cache of intercepted RCS conversation threads
    private val rcsThreads = ConcurrentHashMap<Long, SmsConversationThread>()

    // Thread ID mapping cache (shortcutId or conversation key -> threadId)
    private val conversationThreadIdMap = ConcurrentHashMap<String, Long>()

    fun getAllRcsThreads(): List<SmsConversationThread> {
        return rcsThreads.values.toList().sortedByDescending { it.lastTimestamp }
    }

    fun getRcsThread(threadId: Long): SmsConversationThread? {
        return rcsThreads[threadId]
    }

    fun saveOrUpdateThread(thread: SmsConversationThread) {
        val existing = rcsThreads[thread.threadId]
        if (existing == null) {
            rcsThreads[thread.threadId] = thread
        } else {
            // Merge message maps
            val mergedMessages = (existing.messages + thread.messages).toMutableMap()
            val updated = thread.copy(
                messages = mergedMessages,
                lastTimestamp = maxOf(existing.lastTimestamp, thread.lastTimestamp),
                isRcs = true,
                isEncrypted = existing.isEncrypted || thread.isEncrypted,
                protocol = if (existing.isEncrypted || thread.isEncrypted) "E2EE_RCS" else "RCS"
            )
            rcsThreads[thread.threadId] = updated
        }
    }

    fun normalizeConversationTitle(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        return raw
            .replace("🔒", "")
            .replace(Regex("\\s*\\(\\d+\\s+messages?\\)\\s*$", RegexOption.IGNORE_CASE), "")
            .trim()
            .takeIf { it.isNotBlank() }
    }

    /**
     * Intercepts and parses an active notification for RCS / E2EE MessagingStyle data.
     */
    fun interceptAndProcess(context: Context, sbn: StatusBarNotification): RcsInterceptResult? {
        val pkg = sbn.packageName
        if (!RCS_PACKAGES.contains(pkg)) return null

        val notification = sbn.notification ?: return null
        // Skip Android group summary notifications (e.g. "Group Name (35 messages)") that cause duplicate threads
        if ((notification.flags and Notification.FLAG_GROUP_SUMMARY) != 0) return null
        val extras = notification.extras ?: return null

        // Extract MessagingStyle if present (optional on modern Google Messages)
        val messagingStyle = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(notification)
        val rawMessages = extras.getParcelableArray(Notification.EXTRA_MESSAGES)

        // 1. Detect End-to-End Encryption & RCS Protocol indicators
        val rawTitle = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
            ?: extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
            ?: ""
        val subText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString() ?: ""
        val rawConvTitle = messagingStyle?.conversationTitle?.toString()
            ?: extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString()

        // If no MessagingStyle, no raw messages, and no message text, skip non-message events
        if (messagingStyle == null && rawMessages == null && text.isBlank()) {
            return null
        }

        val cleanTitle = normalizeConversationTitle(rawTitle) ?: ""
        val cleanConvTitle = normalizeConversationTitle(rawConvTitle)
        val cleanSubText = normalizeConversationTitle(subText) ?: ""

        // Google Messages group notifications format: "<Group Name or Participants>: <Sender Name>"
        // e.g. "Dad Valla Olliver II, Mom Bertha Olliver: Mom Bertha Olliver"
        var parsedGroupTitle: String? = cleanConvTitle
        var parsedSenderFromTitle: String? = null

        if (cleanTitle.contains(":")) {
            val candidateGroup = normalizeConversationTitle(cleanTitle.substringBeforeLast(":"))
            val candidateSender = cleanTitle.substringAfterLast(":").trim()
            if (!candidateGroup.isNullOrBlank()) {
                if (parsedGroupTitle.isNullOrBlank()) {
                    parsedGroupTitle = candidateGroup
                }
                parsedSenderFromTitle = candidateSender
            }
        }

        if (parsedGroupTitle.isNullOrBlank() && cleanSubText.isNotBlank() && (cleanSubText.contains(",") || cleanSubText.contains("&"))) {
            parsedGroupTitle = cleanSubText
        }

        val hasLockIndicator = rawTitle.contains("🔒") || text.contains("🔒") || subText.contains("🔒") ||
                (rawConvTitle != null && rawConvTitle.contains("🔒"))
        val hasE2eeText = subText.contains("End-to-end encrypted", ignoreCase = true) ||
                text.contains("End-to-end encrypted", ignoreCase = true) ||
                rawTitle.contains("End-to-end encrypted", ignoreCase = true)

        val isEncrypted = hasLockIndicator || hasE2eeText
        val isRcs = true // From Google/Samsung Messages MessagingStyle
        val protocol = if (isEncrypted) "E2EE_RCS" else "RCS"

        // 2. Typing status
        val isTyping = text.endsWith("is typing...", ignoreCase = true) ||
                text.equals("Typing...", ignoreCase = true) ||
                subText.contains("typing", ignoreCase = true)
        val typingSender = if (isTyping) {
            if (text.contains("is typing")) text.substringBefore("is typing").trim() else (parsedSenderFromTitle ?: cleanTitle)
        } else null

        // 3. Multi-Member Group detection
        val isGroup = messagingStyle?.isGroupConversation == true ||
                extras.getBoolean(Notification.EXTRA_IS_GROUP_CONVERSATION, false) ||
                (!parsedGroupTitle.isNullOrBlank() && (parsedGroupTitle.contains(",") || parsedGroupTitle.contains("&")))

        // 4. Resolve participant phone numbers from contacts & MessagingStyle persons
        val participantsSet = mutableSetOf<String>()
        val participantNames = mutableListOf<String>()

        if (!parsedGroupTitle.isNullOrBlank() && (parsedGroupTitle.contains(",") || parsedGroupTitle.contains("&"))) {
            val delimiters = Regex("[,&]+")
            val names = parsedGroupTitle.split(delimiters).map { it.trim() }.filter { it.isNotBlank() }
            participantNames.addAll(names)
            for (name in names) {
                val phone = SmsRepository.resolvePhoneNumberForContactName(context, name)
                if (phone != null && SmsRepository.isValidPhoneAddress(phone)) {
                    participantsSet.add(phone)
                }
            }
        } else if (!isGroup && cleanTitle.isNotBlank()) {
            val singlePhone = SmsRepository.normalizePhoneNumber(cleanTitle).takeIf { SmsRepository.isValidPhoneAddress(it) }
                ?: SmsRepository.resolvePhoneNumberForContactName(context, parsedSenderFromTitle ?: cleanTitle)
            if (singlePhone != null && SmsRepository.isValidPhoneAddress(singlePhone)) {
                participantsSet.add(singlePhone)
            }
        }

        // Also harvest valid phone numbers from MessagingStyle persons before thread resolution
        messagingStyle?.messages?.forEach { m ->
            val person = m.person ?: return@forEach
            val resolvedFromUri = SmsRepository.resolvePhoneNumberFromUri(context, person.uri)
            val resolvedFromName = person.name?.toString()?.takeIf { it.isNotBlank() }?.let {
                SmsRepository.resolvePhoneNumberForContactName(context, it)
            }
            val candidate = resolvedFromUri ?: resolvedFromName
            if (candidate != null && SmsRepository.isValidPhoneAddress(candidate)) {
                participantsSet.add(candidate)
            }
        }

        // Try to look up existing Telephony thread ID for these participants (both group & 1-on-1)
        var resolvedTelephonyThreadId: Long? = null
        if (participantsSet.isNotEmpty() && com.example.pushbridge.util.PermissionUtils.hasSmsReadPermission(context)) {
            try {
                val tid = Telephony.Threads.getOrCreateThreadId(context, participantsSet)
                if (tid > 0) {
                    resolvedTelephonyThreadId = tid
                    Log.i(TAG, "Resolved RCS conversation '${parsedGroupTitle ?: cleanTitle}' to Telephony thread ID $tid via participants: $participantsSet")
                }
            } catch (e: Exception) {
                Log.d(TAG, "Could not get Telephony thread ID for participants: ${e.message}")
            }
        }

        // Stable Conversation & Thread ID Resolution
        val shortcutId = NotificationCompat.getShortcutId(notification)
        val conversationKey = shortcutId
            ?: parsedGroupTitle
            ?: cleanConvTitle
            ?: sbn.tag
            ?: cleanTitle.ifBlank { sbn.key }

        val threadId = resolvedTelephonyThreadId ?: conversationThreadIdMap.getOrPut(conversationKey) {
            // Allocate stable positive ID in the RCS range (8_000_000_000+)
            8000000000L + Math.abs(conversationKey.hashCode().toLong()) % 1000000000L
        }

        // Register thread-specific RemoteInput reply aliases
        NotificationReplyRegistry.extractAndRegisterReplyAction(sbn)?.let { action ->
            NotificationReplyRegistry.registerReplyAction("rcs_thread_$threadId", action)
            if (shortcutId != null) {
                NotificationReplyRegistry.registerReplyAction("rcs_thread_$shortcutId", action)
            }
            if (resolvedTelephonyThreadId != null) {
                conversationThreadIdMap[conversationKey] = resolvedTelephonyThreadId
            }
            if (!parsedGroupTitle.isNullOrBlank()) {
                conversationThreadIdMap[parsedGroupTitle] = threadId
            }
        }

        // 5. Parse Messages & Media Attachments from MessagingStyle
        val parsedMessages = mutableListOf<SmsMessageItem>()

        val styleMessages = messagingStyle?.messages ?: emptyList()
        if (styleMessages.isNotEmpty()) {
            for (m in styleMessages) {
                val msgText = m.text?.toString() ?: ""
                val msgTime = if (m.timestamp > 0) m.timestamp else sbn.postTime
                val person = m.person
                val senderName = person?.name?.toString() ?: parsedSenderFromTitle ?: cleanTitle.ifBlank { "Participant" }
                val senderUri = person?.uri ?: ""
                var cleanAddress = SmsRepository.resolvePhoneNumberFromUri(context, senderUri) ?: ""

                if (cleanAddress.isBlank()) {
                    cleanAddress = SmsRepository.resolvePhoneNumberForContactName(context, senderName) ?: ""
                }
                if (cleanAddress.isNotBlank() && SmsRepository.isValidPhoneAddress(cleanAddress)) {
                    participantsSet.add(cleanAddress)
                }

                var attachmentData: String? = null
                var attachmentMime: String? = m.dataMimeType

                if (m.dataUri != null) {
                    attachmentData = extractThumbnailBase64(context, m.dataUri!!)
                }

                val msgId = "rcs_${msgTime}_${Math.abs(senderName.hashCode())}"
                parsedMessages.add(
                    SmsMessageItem(
                        id = msgId,
                        threadId = threadId,
                        address = cleanAddress.ifBlank { senderName },
                        contactName = senderName,
                        body = msgText.ifBlank { if (attachmentData != null) "[Photo Attachment]" else "[RCS Message]" },
                        timestamp = msgTime,
                        isIncoming = true,
                        isRead = true,
                        attachmentMime = attachmentMime,
                        attachmentData = attachmentData,
                        isRcs = true,
                        isEncrypted = isEncrypted,
                        protocol = protocol
                    )
                )
            }
        }

        // Fallback if MessagingStyle.messages is empty: parse from current notification text
        if (parsedMessages.isEmpty() && text.isNotBlank()) {
            val msgTime = sbn.postTime
            val senderName = parsedSenderFromTitle ?: cleanTitle.ifBlank { "RCS Contact" }
            val cleanAddr = SmsRepository.resolvePhoneNumberForContactName(context, senderName) ?: senderName
            val msgId = "rcs_${msgTime}_${Math.abs(cleanTitle.hashCode())}"
            parsedMessages.add(
                SmsMessageItem(
                    id = msgId,
                    threadId = threadId,
                    address = cleanAddr,
                    contactName = senderName,
                    body = text,
                    timestamp = msgTime,
                    isIncoming = true,
                    isRead = true,
                    isRcs = true,
                    isEncrypted = isEncrypted,
                    protocol = protocol
                )
            )
        }

        val latest = parsedMessages.lastOrNull() ?: SmsMessageItem(
            id = "rcs_${sbn.postTime}",
            threadId = threadId,
            address = cleanTitle,
            body = text,
            timestamp = sbn.postTime,
            isRcs = true,
            isEncrypted = isEncrypted,
            protocol = protocol
        )

        // 6. Build or Update Conversation Thread
        val existingThread = rcsThreads[threadId]
        val existingMessages = existingThread?.messages ?: emptyMap()
        val updatedMessages = existingMessages.toMutableMap()
        parsedMessages.forEach { msg ->
            updatedMessages[msg.id] = msg.toMap()
        }

        val groupTitleFinal = parsedGroupTitle ?: cleanConvTitle ?: if (isGroup) cleanTitle else null

        // Build recipients list
        val recipientsList = mutableListOf<Map<String, String>>()
        if (participantsSet.isNotEmpty()) {
            for (p in participantsSet) {
                val clean = SmsRepository.normalizePhoneNumber(p)
                val cName = SmsRepository.resolveContactName(context, clean) ?: clean
                recipientsList.add(
                    mapOf(
                        "phone" to clean,
                        "name" to cName,
                        "cleanDigits" to clean.replace(Regex("\\D"), "")
                    )
                )
            }
        } else if (participantNames.isNotEmpty()) {
            for (name in participantNames) {
                recipientsList.add(
                    mapOf(
                        "phone" to name,
                        "name" to name,
                        "cleanDigits" to name.replace(Regex("\\D"), "")
                    )
                )
            }
        } else if (cleanTitle.isNotBlank()) {
            recipientsList.add(
                mapOf(
                    "phone" to cleanTitle,
                    "name" to cleanTitle,
                    "cleanDigits" to cleanTitle.replace(Regex("\\D"), "")
                )
            )
        }

        val displayAddress = if (recipientsList.isNotEmpty()) {
            recipientsList.joinToString(", ") { it["phone"] ?: "" }
        } else {
            cleanTitle.ifBlank { conversationKey }
        }

        val updatedThread = SmsConversationThread(
            threadId = threadId,
            address = displayAddress,
            contactName = groupTitleFinal ?: cleanTitle,
            snippet = latest.body,
            lastTimestamp = latest.timestamp,
            unreadCount = (existingThread?.unreadCount ?: 0) + 1,
            isGroup = isGroup,
            groupTitle = groupTitleFinal,
            recipients = recipientsList,
            messages = updatedMessages,
            isRcs = true,
            isEncrypted = isEncrypted,
            protocol = protocol,
            isTyping = isTyping,
            typingSender = typingSender
        )

        rcsThreads[threadId] = updatedThread

        val smsPayload = SmsPayload(
            id = latest.id,
            threadId = threadId,
            sender = latest.contactName ?: latest.address,
            body = latest.body,
            timestamp = latest.timestamp,
            isGroup = isGroup,
            recipients = recipientsList.map { it["phone"] ?: "" }.filter { it.isNotBlank() },
            attachmentData = latest.attachmentData,
            isRcs = true,
            isEncrypted = isEncrypted,
            protocol = protocol,
            groupTitle = groupTitleFinal
        )

        Log.i(TAG, "Intercepted RCS ($protocol) message from '${smsPayload.sender}' (Thread $threadId, isGroup=$isGroup, groupTitle='$groupTitleFinal')")

        return RcsInterceptResult(
            isRcs = true,
            thread = updatedThread,
            latestMessage = latest,
            smsPayload = smsPayload
        )
    }

    /**
     * Merges in-memory intercepted RCS conversation threads with standard telephony threads.
     */
    fun mergeWithTelephonyThreads(
        context: Context,
        telephonyThreads: List<SmsConversationThread>
    ): List<SmsConversationThread> {
        val mergedList = telephonyThreads.toMutableList()
        val matchedRcsThreadIds = mutableSetOf<Long>()

        // 1. Cross-reference telephony threads and enrich matching conversations with RCS data
        for (i in mergedList.indices) {
            val tThread = mergedList[i]
            val cleanAddr = SmsRepository.normalizePhoneNumber(tThread.address)

            // Look for matching RCS thread by threadId, address, groupTitle, or contactName
            val matchingRcs = rcsThreads.values.firstOrNull { rcs ->
                rcs.threadId == tThread.threadId ||
                        (cleanAddr.isNotBlank() && cleanAddr == SmsRepository.normalizePhoneNumber(rcs.address)) ||
                        (!rcs.groupTitle.isNullOrBlank() && (
                                rcs.groupTitle.equals(tThread.groupTitle, ignoreCase = true) ||
                                tThread.contactName?.equals(rcs.groupTitle, ignoreCase = true) == true ||
                                tThread.groupTitle?.let { gt ->
                                    rcs.groupTitle.contains(gt, ignoreCase = true) ||
                                    gt.contains(rcs.groupTitle, ignoreCase = true)
                                } == true
                        )) ||
                        (!rcs.contactName.isNullOrBlank() && (
                                rcs.contactName.equals(tThread.groupTitle, ignoreCase = true) ||
                                rcs.contactName.equals(tThread.contactName, ignoreCase = true)
                        ))
            }

            if (matchingRcs != null) {
                matchedRcsThreadIds.add(matchingRcs.threadId)
                val combinedMessages = (tThread.messages + matchingRcs.messages).toMutableMap()
                val latestTime = maxOf(tThread.lastTimestamp, matchingRcs.lastTimestamp)
                val snippetFinal = if (matchingRcs.lastTimestamp >= tThread.lastTimestamp) matchingRcs.snippet else tThread.snippet

                mergedList[i] = tThread.copy(
                    snippet = snippetFinal,
                    lastTimestamp = latestTime,
                    messages = combinedMessages,
                    isRcs = true,
                    isEncrypted = matchingRcs.isEncrypted,
                    protocol = matchingRcs.protocol,
                    isTyping = matchingRcs.isTyping,
                    typingSender = matchingRcs.typingSender,
                    groupTitle = tThread.groupTitle ?: matchingRcs.groupTitle,
                    isGroup = tThread.isGroup || matchingRcs.isGroup
                )

                // Register reply action alias under the Telephony thread ID as well
                NotificationReplyRegistry.getReplyAction("rcs_thread_${matchingRcs.threadId}")?.let { action ->
                    NotificationReplyRegistry.registerReplyAction("rcs_thread_${tThread.threadId}", action)
                }
            }
        }

        // 2. Add RCS threads that were not in Telephony (e.g. pure Google Messages RCS group chats)
        for ((rcsId, rcsThread) in rcsThreads) {
            if (!matchedRcsThreadIds.contains(rcsId)) {
                mergedList.add(rcsThread)
            }
        }

        return mergedList.sortedByDescending { it.lastTimestamp }
    }

    /**
     * Extracts a downsampled JPEG Base64 thumbnail from a content URI.
     */
    private fun extractThumbnailBase64(context: Context, uri: Uri): String? {
        return try {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                val bitmap = BitmapFactory.decodeStream(stream) ?: return null
                val maxDim = 480
                val scaled = if (bitmap.width > maxDim || bitmap.height > maxDim) {
                    val factor = Math.min(maxDim.toFloat() / bitmap.width, maxDim.toFloat() / bitmap.height)
                    Bitmap.createScaledBitmap(bitmap, (bitmap.width * factor).toInt(), (bitmap.height * factor).toInt(), true)
                } else bitmap

                val baos = ByteArrayOutputStream()
                scaled.compress(Bitmap.CompressFormat.JPEG, 70, baos)
                val b64 = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
                "data:image/jpeg;base64,$b64"
            }
        } catch (e: Exception) {
            Log.d(TAG, "Failed reading RCS media URI $uri: ${e.message}")
            null
        }
    }
}
