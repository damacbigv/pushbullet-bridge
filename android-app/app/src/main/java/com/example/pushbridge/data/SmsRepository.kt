package com.example.pushbridge.data

import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.ContactsContract
import android.provider.Telephony
import android.util.Log
import com.example.pushbridge.model.DeviceContactItem
import com.example.pushbridge.model.SmsConversationThread
import com.example.pushbridge.model.SmsMessageItem
import com.example.pushbridge.util.PermissionUtils
import java.io.BufferedReader
import java.io.InputStreamReader

object SmsRepository {
    private const val TAG = "SmsRepository"

    // MMS PDU Header Address types
    private const val MMS_ADDR_TYPE_BCC = 129
    private const val MMS_ADDR_TYPE_CC = 130
    private const val MMS_ADDR_TYPE_FROM = 137
    private const val MMS_ADDR_TYPE_TO = 151

    /**
     * Reads SMS & MMS conversations and thread histories from Android Telephony provider.
     * Accurately aggregates multi-recipient group conversations via canonical addresses
     * and MMS part address tables (FROM, TO, CC).
     */
    fun queryConversationThreads(
        context: Context,
        maxThreads: Int = 30,
        maxMessagesPerThread: Int = 100
    ): List<SmsConversationThread> {
        if (!PermissionUtils.hasSmsReadPermission(context)) {
            Log.w(TAG, "Cannot query SMS threads: READ_SMS permission not granted.")
            return emptyList()
        }

        // Cache resolved contact names and canonical addresses to minimize query overhead
        val contactNameCache = mutableMapOf<String, String?>()
        val canonicalAddressCache = mutableMapOf<String, String>()

        // 1. Discover threads from Telephony conversations table (content://mms-sms/conversations)
        val conversationMetadataMap = queryConversationMetadata(context, canonicalAddressCache)

        // 2. Query individual SMS messages grouped by threadId
        val threadSmsMap = mutableMapOf<Long, MutableList<SmsMessageItem>>()
        val threadAddressesFallback = mutableMapOf<Long, String>()
        val threadUnreadCounts = mutableMapOf<Long, Int>()

        val projection = arrayOf(
            Telephony.Sms._ID,
            Telephony.Sms.THREAD_ID,
            Telephony.Sms.ADDRESS,
            Telephony.Sms.BODY,
            Telephony.Sms.DATE,
            Telephony.Sms.TYPE,
            Telephony.Sms.READ
        )

        try {
            val cursor = context.contentResolver.query(
                Telephony.Sms.CONTENT_URI,
                projection,
                null,
                null,
                "${Telephony.Sms.DATE} DESC"
            )

            cursor?.use { c ->
                val idCol = c.getColumnIndexOrThrow(Telephony.Sms._ID)
                val threadIdCol = c.getColumnIndexOrThrow(Telephony.Sms.THREAD_ID)
                val addressCol = c.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)
                val bodyCol = c.getColumnIndexOrThrow(Telephony.Sms.BODY)
                val dateCol = c.getColumnIndexOrThrow(Telephony.Sms.DATE)
                val typeCol = c.getColumnIndexOrThrow(Telephony.Sms.TYPE)
                val readCol = c.getColumnIndexOrThrow(Telephony.Sms.READ)

                while (c.moveToNext()) {
                    val threadId = c.getLong(threadIdCol)
                    val list = threadSmsMap.getOrPut(threadId) { mutableListOf() }

                    if (list.size >= maxMessagesPerThread) {
                        continue
                    }

                    val id = "sms_" + c.getString(idCol)
                    val rawAddr = c.getString(addressCol) ?: ""
                    val normalizedAddr = normalizePhoneNumber(rawAddr)
                    val body = c.getString(bodyCol) ?: ""
                    val timestamp = c.getLong(dateCol)
                    val type = c.getInt(typeCol)
                    val read = c.getInt(readCol) == 1
                    val isIncoming = type == Telephony.Sms.MESSAGE_TYPE_INBOX

                    if (!threadAddressesFallback.containsKey(threadId) && normalizedAddr.isNotBlank()) {
                        threadAddressesFallback[threadId] = normalizedAddr
                    }

                    if (!read && isIncoming) {
                        threadUnreadCounts[threadId] = (threadUnreadCounts[threadId] ?: 0) + 1
                    }

                    list.add(
                        SmsMessageItem(
                            id = id,
                            threadId = threadId,
                            address = normalizedAddr.ifBlank { "Unknown" },
                            contactName = null,
                            body = body,
                            timestamp = timestamp,
                            isIncoming = isIncoming,
                            isRead = read
                        )
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error querying SMS content provider: ${e.message}", e)
        }

        // 3. Query MMS messages & parse MMS part addresses (identifies all group chat participants)
        val threadMmsParticipants = mutableMapOf<Long, MutableSet<String>>()
        val threadMmsMap = queryMmsMessages(
            context = context,
            maxMessagesPerThread = maxMessagesPerThread,
            threadMmsParticipants = threadMmsParticipants
        )

        // Combine all discovered thread IDs
        val allThreadIds = (conversationMetadataMap.keys + threadSmsMap.keys + threadMmsMap.keys).toSet()

        val resultThreads = mutableListOf<SmsConversationThread>()

        for (threadId in allThreadIds) {
            val convMeta = conversationMetadataMap[threadId]
            val smsList = threadSmsMap[threadId] ?: emptyList()
            val mmsList = threadMmsMap[threadId] ?: emptyList()

            // Merge and sort chronologically (oldest to newest)
            val combinedMessages = (smsList + mmsList).sortedBy { it.timestamp }
            val finalMessages = if (combinedMessages.size > maxMessagesPerThread) {
                combinedMessages.takeLast(maxMessagesPerThread)
            } else {
                combinedMessages
            }

            if (finalMessages.isEmpty() && convMeta == null) continue

            // 4. Multi-Member Participant Aggregation:
            // Combines addresses from Telephony canonical-addresses AND MMS part addresses (FROM, TO, CC)
            val aggregatedParticipants = mutableSetOf<String>()

            // Add metadata recipients
            if (convMeta != null && convMeta.recipients.isNotEmpty()) {
                convMeta.recipients.forEach { recip ->
                    val clean = normalizePhoneNumber(recip)
                    if (clean.isNotBlank()) aggregatedParticipants.add(clean)
                }
            }

            // Add MMS participants (captures family threads with multiple members)
            val mmsAddrs = threadMmsParticipants[threadId]
            if (!mmsAddrs.isNullOrEmpty()) {
                mmsAddrs.forEach { recip ->
                    val clean = normalizePhoneNumber(recip)
                    if (clean.isNotBlank()) aggregatedParticipants.add(clean)
                }
            }

            // Fallback to SMS address if participant list is empty
            val smsFallback = threadAddressesFallback[threadId]
            if (aggregatedParticipants.isEmpty() && !smsFallback.isNullOrBlank()) {
                val clean = normalizePhoneNumber(smsFallback)
                if (clean.isNotBlank()) aggregatedParticipants.add(clean)
            }

            if (aggregatedParticipants.isEmpty() && finalMessages.isNotEmpty()) {
                finalMessages.forEach { msg ->
                    if (msg.address != "Me" && msg.address != "Unknown") {
                        val clean = normalizePhoneNumber(msg.address)
                        if (clean.isNotBlank()) aggregatedParticipants.add(clean)
                    }
                }
            }

            // 5. Determine whether this is a multi-recipient group thread
            val isGroup = aggregatedParticipants.size > 1 || (convMeta != null && convMeta.recipients.size > 1)
            val recipientsList = mutableListOf<Map<String, String>>()
            val groupTitle: String?
            val rawAddress: String

            if (isGroup) {
                for (phone in aggregatedParticipants) {
                    val contactName = contactNameCache.getOrPut(phone) {
                        resolveContactName(context, phone)
                    }
                    val cleanDigits = phone.replace(Regex("\\D"), "")
                    recipientsList.add(
                        mapOf(
                            "phone" to phone,
                            "name" to (contactName ?: phone),
                            "cleanDigits" to cleanDigits
                        )
                    )
                }

                // Sort recipients alphabetically by name for a consistent group title
                recipientsList.sortBy { it["name"] ?: it["phone"] ?: "" }
                groupTitle = recipientsList.joinToString(", ") { it["name"] ?: it["phone"] ?: "" }
                rawAddress = recipientsList.joinToString(", ") { it["phone"] ?: "" }
            } else {
                val singlePhone = aggregatedParticipants.firstOrNull() ?: threadAddressesFallback[threadId] ?: "Unknown"
                rawAddress = singlePhone
                groupTitle = null
                if (singlePhone != "Unknown") {
                    val contactName = contactNameCache.getOrPut(singlePhone) {
                        resolveContactName(context, singlePhone)
                    }
                    val cleanDigits = singlePhone.replace(Regex("\\D"), "")
                    recipientsList.add(
                        mapOf(
                            "phone" to singlePhone,
                            "name" to (contactName ?: singlePhone),
                            "cleanDigits" to cleanDigits
                        )
                    )
                }
            }

            val contactName = if (isGroup) {
                groupTitle
            } else {
                contactNameCache.getOrPut(rawAddress) {
                    resolveContactName(context, rawAddress)
                }
            }

            val latestMessage = finalMessages.lastOrNull()
            val lastTimestamp = latestMessage?.timestamp
                ?: convMeta?.date
                ?: System.currentTimeMillis()
            val snippet = latestMessage?.body
                ?: convMeta?.snippet
                ?: ""

            // 6. Build map of messages for Firebase RTDB with contact names resolved per bubble
            val messagesMap = finalMessages.associate { msg ->
                val msgContact = if (msg.isIncoming && msg.address != "Me" && msg.address != "Unknown") {
                    contactNameCache.getOrPut(msg.address) {
                        resolveContactName(context, msg.address)
                    }
                } else if (!msg.isIncoming) {
                    "Me"
                } else null

                val enriched = msg.copy(contactName = msgContact)
                enriched.id to enriched.toMap()
            }

            resultThreads.add(
                SmsConversationThread(
                    threadId = threadId,
                    address = rawAddress,
                    contactName = contactName,
                    snippet = snippet,
                    lastTimestamp = lastTimestamp,
                    unreadCount = threadUnreadCounts[threadId] ?: convMeta?.unreadCount ?: 0,
                    isGroup = isGroup,
                    groupTitle = groupTitle,
                    recipients = recipientsList,
                    messages = messagesMap
                )
            )
        }

        // 7. Merge with active and cached RCS / E2EE conversation threads
        val mergedWithRcs = RcsConversationManager.mergeWithTelephonyThreads(context, resultThreads)
        val sorted = mergedWithRcs.sortedByDescending { it.lastTimestamp }
        return if (sorted.size > maxThreads) sorted.take(maxThreads) else sorted
    }

    /**
     * Reads conversations metadata from `content://mms-sms/conversations?simple=true`
     * with fallback to `Telephony.Threads.CONTENT_URI`.
     * Pre-populates canonical addresses in bulk to reliably resolve recipient IDs.
     */
    private fun queryConversationMetadata(
        context: Context,
        canonicalAddressCache: MutableMap<String, String>
    ): Map<Long, ConversationMetadata> {
        val map = mutableMapOf<Long, ConversationMetadata>()

        // Pre-populate canonical addresses in one bulk query
        loadAllCanonicalAddresses(context, canonicalAddressCache)

        val uri = Uri.parse("content://mms-sms/conversations?simple=true")
        try {
            val cursor = context.contentResolver.query(
                uri,
                arrayOf("_id", "date", "message_count", "recipient_ids", "snippet", "read"),
                null,
                null,
                "date DESC"
            )

            cursor?.use { c ->
                val idCol = c.getColumnIndex("_id")
                val dateCol = c.getColumnIndex("date")
                val countCol = c.getColumnIndex("message_count")
                val recipCol = c.getColumnIndex("recipient_ids")
                val snippetCol = c.getColumnIndex("snippet")
                val readCol = c.getColumnIndex("read")

                while (c.moveToNext()) {
                    if (idCol == -1) break
                    val threadId = c.getLong(idCol)
                    val date = if (dateCol != -1) c.getLong(dateCol) else System.currentTimeMillis()
                    val count = if (countCol != -1) c.getInt(countCol) else 0
                    val recipIdsRaw = if (recipCol != -1) c.getString(recipCol) ?: "" else ""
                    val snippet = if (snippetCol != -1) c.getString(snippetCol) ?: "" else ""
                    val read = if (readCol != -1) c.getInt(readCol) == 1 else true

                    val recipientIds = recipIdsRaw.split(" ").filter { it.isNotBlank() }
                    val phoneNumbers = recipientIds.mapNotNull { recipId ->
                        canonicalAddressCache.getOrPut(recipId) {
                            resolveCanonicalAddress(context, recipId) ?: ""
                        }.takeIf { it.isNotBlank() }
                    }

                    map[threadId] = ConversationMetadata(
                        threadId = threadId,
                        date = date,
                        messageCount = count,
                        recipients = phoneNumbers,
                        snippet = snippet,
                        unreadCount = if (!read) 1 else 0
                    )
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "Conversation simple=true query not supported or failed: ${e.message}")
        }

        // Fallback to Telephony.Threads.CONTENT_URI if simple=true query returned empty
        if (map.isEmpty()) {
            try {
                val fallbackUri = Telephony.Threads.CONTENT_URI
                val cursor = context.contentResolver.query(
                    fallbackUri,
                    arrayOf("_id", "date", "message_count", "recipient_ids", "snippet", "read"),
                    null,
                    null,
                    "date DESC"
                )

                cursor?.use { c ->
                    val idCol = c.getColumnIndex("_id")
                    val dateCol = c.getColumnIndex("date")
                    val countCol = c.getColumnIndex("message_count")
                    val recipCol = c.getColumnIndex("recipient_ids")
                    val snippetCol = c.getColumnIndex("snippet")
                    val readCol = c.getColumnIndex("read")

                    while (c.moveToNext()) {
                        if (idCol == -1) break
                        val threadId = c.getLong(idCol)
                        val date = if (dateCol != -1) c.getLong(dateCol) else System.currentTimeMillis()
                        val count = if (countCol != -1) c.getInt(countCol) else 0
                        val recipIdsRaw = if (recipCol != -1) c.getString(recipCol) ?: "" else ""
                        val snippet = if (snippetCol != -1) c.getString(snippetCol) ?: "" else ""
                        val read = if (readCol != -1) c.getInt(readCol) == 1 else true

                        val recipientIds = recipIdsRaw.split(" ").filter { it.isNotBlank() }
                        val phoneNumbers = recipientIds.mapNotNull { recipId ->
                            canonicalAddressCache.getOrPut(recipId) {
                                resolveCanonicalAddress(context, recipId) ?: ""
                            }.takeIf { it.isNotBlank() }
                        }

                        map[threadId] = ConversationMetadata(
                            threadId = threadId,
                            date = date,
                            messageCount = count,
                            recipients = phoneNumbers,
                            snippet = snippet,
                            unreadCount = if (!read) 1 else 0
                        )
                    }
                }
            } catch (e: Exception) {
                Log.d(TAG, "Telephony.Threads fallback query failed: ${e.message}")
            }
        }

        return map
    }

    /**
     * Loads all canonical recipient IDs to phone numbers in one bulk query.
     */
    private fun loadAllCanonicalAddresses(
        context: Context,
        cache: MutableMap<String, String>
    ) {
        try {
            val uri = Uri.parse("content://mms-sms/canonical-addresses")
            val cursor = context.contentResolver.query(
                uri,
                arrayOf("_id", "address"),
                null,
                null,
                null
            )
            cursor?.use { c ->
                val idCol = c.getColumnIndex("_id")
                val addrCol = c.getColumnIndex("address")
                while (c.moveToNext()) {
                    if (idCol != -1 && addrCol != -1) {
                        val id = c.getString(idCol)
                        val addr = c.getString(addrCol)
                        if (!id.isNullOrBlank() && !addr.isNullOrBlank()) {
                            val clean = normalizePhoneNumber(addr)
                            if (clean.isNotBlank()) {
                                cache[id] = clean
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "Bulk canonical-addresses query not available: ${e.message}")
        }
    }

    /**
     * Resolves a Telephony recipient_id to a phone number via canonical addresses fallback.
     */
    private fun resolveCanonicalAddress(context: Context, recipientId: String): String? {
        val uri = Uri.parse("content://mms-sms/canonical-address/$recipientId")
        return try {
            val cursor = context.contentResolver.query(uri, null, null, null, null)
            cursor?.use { c ->
                if (c.moveToFirst()) {
                    val raw = c.getString(0)
                    normalizePhoneNumber(raw).takeIf { it.isNotBlank() }
                } else null
            }
        } catch (e: Exception) {
            Log.d(TAG, "Failed resolving canonical address for $recipientId: ${e.message}")
            null
        }
    }

    /**
     * Queries MMS messages for rich group chat text bodies, image parts, and participant addresses.
     */
    private fun queryMmsMessages(
        context: Context,
        maxMessagesPerThread: Int,
        threadMmsParticipants: MutableMap<Long, MutableSet<String>>
    ): Map<Long, List<SmsMessageItem>> {
        val mmsMap = mutableMapOf<Long, MutableList<SmsMessageItem>>()
        val uri = Uri.parse("content://mms")

        try {
            val projection = arrayOf("_id", "thread_id", "date", "msg_box", "read", "m_type")
            val cursor = context.contentResolver.query(
                uri,
                projection,
                null,
                null,
                "date DESC"
            )

            cursor?.use { c ->
                val idCol = c.getColumnIndex("_id")
                val threadIdCol = c.getColumnIndex("thread_id")
                val dateCol = c.getColumnIndex("date")
                val msgBoxCol = c.getColumnIndex("msg_box")
                val readCol = c.getColumnIndex("read")
                val mTypeCol = c.getColumnIndex("m_type")

                while (c.moveToNext()) {
                    if (idCol == -1) break
                    val mmsId = c.getLong(idCol)
                    val rawThreadId = if (threadIdCol != -1) c.getLong(threadIdCol) else 0L

                    // Skip m-notification-ind (130) stubs waiting for PDU download
                    val mType = if (mTypeCol != -1) c.getInt(mTypeCol) else 0
                    if (mType == 130) continue

                    // Telephony MMS date is in seconds
                    val rawDate = if (dateCol != -1) c.getLong(dateCol) else 0L
                    val timestamp = if (rawDate < 10000000000L) rawDate * 1000L else rawDate
                    val msgBox = if (msgBoxCol != -1) c.getInt(msgBoxCol) else 1
                    val read = if (readCol != -1) c.getInt(readCol) == 1 else true
                    val isIncoming = msgBox == 1 // 1 = inbox, 2 = sent, 4 = outbox

                    // Parse MMS addresses: extracts sender and group participants
                    val addrDetails = queryMmsAddresses(context, mmsId)

                    // Dynamic thread_id resolution for group MMS with 0 or null thread_id
                    var effectiveThreadId = rawThreadId
                    if (effectiveThreadId <= 0 && addrDetails.allParticipants.isNotEmpty()) {
                        try {
                            effectiveThreadId = Telephony.Threads.getOrCreateThreadId(context, addrDetails.allParticipants)
                        } catch (_: Exception) {
                            try {
                                effectiveThreadId = Telephony.Threads.getOrCreateThreadId(context, addrDetails.allParticipants.first())
                            } catch (_: Exception) {}
                        }
                    }

                    if (effectiveThreadId <= 0) continue

                    val list = mmsMap.getOrPut(effectiveThreadId) { mutableListOf() }
                    if (list.size >= maxMessagesPerThread) continue

                    if (addrDetails.allParticipants.isNotEmpty()) {
                        threadMmsParticipants.getOrPut(effectiveThreadId) { mutableSetOf() }.addAll(addrDetails.allParticipants)
                    }

                    val mmsContent = getMmsContent(context, mmsId)
                    val senderAddress = if (isIncoming) {
                        addrDetails.sender ?: addrDetails.recipients.firstOrNull() ?: "Unknown"
                    } else {
                        "Me"
                    }

                    list.add(
                        SmsMessageItem(
                            id = "mms_$mmsId",
                            threadId = effectiveThreadId,
                            address = senderAddress,
                            contactName = null,
                            body = mmsContent.text,
                            timestamp = timestamp,
                            isIncoming = isIncoming,
                            isRead = read,
                            attachmentData = mmsContent.attachmentData,
                            attachmentMime = mmsContent.attachmentMime
                        )
                    )
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "MMS query failed or not supported: ${e.message}")
        }

        return mmsMap
    }

    private data class MmsAddressDetails(
        val sender: String?,
        val recipients: List<String>,
        val allParticipants: Set<String>
    )

    /**
     * Parses MMS addresses from `content://mms/{mmsId}/addr`.
     * Identifies sender (type=137) and recipients (type=151, 130, 129),
     * filtering out insert-address-token placeholders.
     */
    private fun queryMmsAddresses(context: Context, mmsId: Long): MmsAddressDetails {
        val uri = Uri.parse("content://mms/$mmsId/addr")
        val recipients = mutableListOf<String>()
        var sender: String? = null
        val allParticipants = mutableSetOf<String>()

        try {
            val cursor = context.contentResolver.query(
                uri,
                arrayOf("address", "type"),
                null,
                null,
                null
            )
            cursor?.use { c ->
                val addrCol = c.getColumnIndex("address")
                val typeCol = c.getColumnIndex("type")

                while (c.moveToNext()) {
                    val rawAddr = if (addrCol != -1) c.getString(addrCol) else null
                    val type = if (typeCol != -1) c.getInt(typeCol) else 0

                    if (rawAddr.isNullOrBlank() || rawAddr.equals("insert-address-token", ignoreCase = true)) {
                        continue
                    }

                    val cleanPhone = normalizePhoneNumber(rawAddr)
                    if (cleanPhone.isBlank()) continue

                    allParticipants.add(cleanPhone)

                    when (type) {
                        MMS_ADDR_TYPE_FROM -> {
                            if (sender == null) sender = cleanPhone
                        }
                        MMS_ADDR_TYPE_TO, MMS_ADDR_TYPE_CC, MMS_ADDR_TYPE_BCC -> {
                            if (!recipients.contains(cleanPhone)) {
                                recipients.add(cleanPhone)
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "Failed querying MMS addresses for mmsId $mmsId: ${e.message}")
        }

        return MmsAddressDetails(sender, recipients, allParticipants)
    }

    private data class MmsContentResult(
        val text: String,
        val attachmentData: String? = null,
        val attachmentMime: String? = null
    )

    /**
     * Extracts text content and inline image thumbnails from MMS parts table.
     */
    private fun getMmsContent(context: Context, mmsId: Long): MmsContentResult {
        val uri = Uri.parse("content://mms/part")
        val selection = "mid = ?"
        val selectionArgs = arrayOf(mmsId.toString())

        var textBody = ""
        var attachmentData: String? = null
        var attachmentMime: String? = null

        try {
            val cursor = context.contentResolver.query(
                uri,
                arrayOf("_id", "ct", "text", "_data"),
                selection,
                selectionArgs,
                null
            )
            cursor?.use { c ->
                val idCol = c.getColumnIndex("_id")
                val ctCol = c.getColumnIndex("ct")
                val textCol = c.getColumnIndex("text")

                val sb = StringBuilder()

                while (c.moveToNext()) {
                    val ct = if (ctCol != -1) c.getString(ctCol) ?: "" else ""
                    val partId = if (idCol != -1) c.getString(idCol) else ""

                    if (ct.startsWith("text/")) {
                        val directText = if (textCol != -1) c.getString(textCol) else null
                        if (!directText.isNullOrBlank()) {
                            sb.append(directText)
                        } else if (partId.isNotBlank()) {
                            val partUri = Uri.parse("content://mms/part/$partId")
                            try {
                                context.contentResolver.openInputStream(partUri)?.use { isStream ->
                                    val reader = BufferedReader(InputStreamReader(isStream))
                                    var line = reader.readLine()
                                    while (line != null) {
                                        sb.append(line)
                                        line = reader.readLine()
                                    }
                                }
                            } catch (_: Exception) {}
                        }
                    } else if (ct.startsWith("image/") && attachmentData == null && partId.isNotBlank()) {
                        // Extract and downsample MMS image thumbnail
                        val partUri = Uri.parse("content://mms/part/$partId")
                        try {
                            context.contentResolver.openInputStream(partUri)?.use { isStream ->
                                val bitmap = android.graphics.BitmapFactory.decodeStream(isStream)
                                if (bitmap != null) {
                                    val maxDim = 480
                                    val scale = if (bitmap.width > maxDim || bitmap.height > maxDim) {
                                        val factor = Math.min(
                                            maxDim.toFloat() / bitmap.width,
                                            maxDim.toFloat() / bitmap.height
                                        )
                                        android.graphics.Bitmap.createScaledBitmap(
                                            bitmap,
                                            (bitmap.width * factor).toInt(),
                                            (bitmap.height * factor).toInt(),
                                            true
                                        )
                                    } else {
                                        bitmap
                                    }
                                    val baos = java.io.ByteArrayOutputStream()
                                    scale.compress(android.graphics.Bitmap.CompressFormat.JPEG, 70, baos)
                                    val bytes = baos.toByteArray()
                                    val b64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
                                    attachmentData = "data:image/jpeg;base64,$b64"
                                    attachmentMime = ct
                                }
                            }
                        } catch (e: Exception) {
                            Log.d(TAG, "Failed decoding MMS image thumbnail for part $partId: ${e.message}")
                        }
                    }
                }
                textBody = if (sb.isNotEmpty()) sb.toString() else if (attachmentData != null) "[Photo]" else "[MMS Message]"
            }
        } catch (e: Exception) {
            textBody = "[MMS Message]"
        }

        return MmsContentResult(textBody, attachmentData, attachmentMime)
    }

    /**
     * Resolves phone number to Google / Device Address Book contact display name.
     */
    fun resolveContactName(context: Context, phoneNumber: String): String? {
        if (phoneNumber.isBlank() || !PermissionUtils.hasContactsReadPermission(context)) {
            return null
        }

        return try {
            val uri = Uri.withAppendedPath(
                ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
                Uri.encode(phoneNumber)
            )
            val projection = arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME)
            val cursor = context.contentResolver.query(uri, projection, null, null, null)

            cursor?.use {
                if (it.moveToFirst()) {
                    it.getString(it.getColumnIndexOrThrow(ContactsContract.PhoneLookup.DISPLAY_NAME))
                } else {
                    null
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "Could not resolve contact name for $phoneNumber: ${e.message}")
            null
        }
    }

    /**
     * Resolves contact name to their canonical phone number from the device address book.
     */
    fun resolvePhoneNumberForContactName(context: Context, contactName: String): String? {
        if (contactName.isBlank() || !PermissionUtils.hasContactsReadPermission(context)) {
            return null
        }

        return try {
            val projection = arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER)
            val selection = "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} = ? OR ${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?"
            val selectionArgs = arrayOf(contactName.trim(), "%${contactName.trim()}%")

            val cursor = context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                projection,
                selection,
                selectionArgs,
                null
            )

            cursor?.use {
                if (it.moveToFirst()) {
                    val rawNum = it.getString(it.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER)) ?: ""
                    val normalized = normalizePhoneNumber(rawNum)
                    if (normalized.isNotBlank()) normalized else null
                } else null
            }
        } catch (e: Exception) {
            Log.d(TAG, "Could not resolve phone number for contact '$contactName': ${e.message}")
            null
        }
    }

    /**
     * Queries device address book contacts to provide fast local sync to Firebase.
     */
    fun queryDeviceContacts(context: Context, limit: Int = 300): List<DeviceContactItem> {
        if (!PermissionUtils.hasContactsReadPermission(context)) {
            Log.w(TAG, "Cannot query contacts: READ_CONTACTS permission not granted.")
            return emptyList()
        }

        val list = mutableListOf<DeviceContactItem>()
        val seenPhones = mutableSetOf<String>()

        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER
        )

        try {
            val cursor = context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                projection,
                null,
                null,
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC"
            )

            cursor?.use { c ->
                val idCol = c.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.CONTACT_ID)
                val nameCol = c.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val numberCol = c.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER)

                while (c.moveToNext()) {
                    val id = c.getString(idCol)
                    val name = c.getString(nameCol) ?: ""
                    val phone = c.getString(numberCol) ?: ""
                    val cleanDigits = phone.replace(Regex("\\D"), "")

                    if (cleanDigits.length >= 7 && seenPhones.add(cleanDigits)) {
                        list.add(
                            DeviceContactItem(
                                id = id,
                                name = name.ifBlank { phone },
                                phone = phone,
                                cleanDigits = cleanDigits
                            )
                        )
                        if (list.size >= limit) break
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error querying device contacts: ${e.message}", e)
        }

        return list
    }

    /**
     * Standardizes phone numbers to clean digits / E.164 format and filters placeholder tokens.
     */
    fun normalizePhoneNumber(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        val trimmed = raw.trim()
        if (trimmed.equals("insert-address-token", ignoreCase = true)) return ""
        if (trimmed.startsWith("mailto:", ignoreCase = true)) return ""

        val digits = trimmed.replace(Regex("\\D"), "")
        if (digits.length >= 10) {
            return if (trimmed.startsWith("+")) trimmed else if (digits.length == 10) "+1$digits" else "+$digits"
        }
        return trimmed
    }

    private data class ConversationMetadata(
        val threadId: Long,
        val date: Long,
        val messageCount: Int,
        val recipients: List<String>,
        val snippet: String,
        val unreadCount: Int
    )
}
