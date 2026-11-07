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
    private const val MMS_ADDR_TYPE_FROM = 137

    /**
     * Reads SMS & MMS conversations and thread histories from Android Telephony provider.
     * Accurately detects multi-recipient group conversations via canonical addresses.
     */
    fun queryConversationThreads(
        context: Context,
        maxThreads: Int = 30,
        maxMessagesPerThread: Int = 50
    ): List<SmsConversationThread> {
        if (!PermissionUtils.hasSmsReadPermission(context)) {
            Log.w(TAG, "Cannot query SMS threads: READ_SMS permission not granted.")
            return emptyList()
        }

        // Cache resolved contact names by phone number to minimize query overhead
        val contactNameCache = mutableMapOf<String, String?>()
        val canonicalAddressCache = mutableMapOf<String, String>()

        // 1. First discover threads from Telephony conversations table (simple=true)
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
                    val address = c.getString(addressCol) ?: "Unknown"
                    val body = c.getString(bodyCol) ?: ""
                    val timestamp = c.getLong(dateCol)
                    val type = c.getInt(typeCol)
                    val read = c.getInt(readCol) == 1
                    val isIncoming = type == Telephony.Sms.MESSAGE_TYPE_INBOX

                    if (!threadAddressesFallback.containsKey(threadId) && address.isNotBlank()) {
                        threadAddressesFallback[threadId] = address
                    }

                    if (!read && isIncoming) {
                        threadUnreadCounts[threadId] = (threadUnreadCounts[threadId] ?: 0) + 1
                    }

                    list.add(
                        SmsMessageItem(
                            id = id,
                            threadId = threadId,
                            address = address,
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

        // 3. Query MMS messages (including group chats)
        val threadMmsMap = queryMmsMessages(context, maxMessagesPerThread)

        // Combine all discovered thread IDs
        val allThreadIds = (conversationMetadataMap.keys + threadSmsMap.keys + threadMmsMap.keys)

        val resultThreads = mutableListOf<SmsConversationThread>()

        for (threadId in allThreadIds) {
            val convMeta = conversationMetadataMap[threadId]
            val smsList = threadSmsMap[threadId] ?: emptyList()
            val mmsList = threadMmsMap[threadId] ?: emptyList()

            val combinedMessages = (smsList + mmsList).sortedBy { it.timestamp }
            if (combinedMessages.isEmpty() && convMeta == null) continue

            val isGroup: Boolean
            val recipientsList = mutableListOf<Map<String, String>>()
            var groupTitle: String? = null
            val rawAddress: String

            if (convMeta != null && convMeta.recipients.size > 1) {
                // Multi-recipient group conversation detected
                isGroup = true
                rawAddress = convMeta.recipients.joinToString(", ")
                for (recipPhone in convMeta.recipients) {
                    val contactName = contactNameCache.getOrPut(recipPhone) {
                        resolveContactName(context, recipPhone)
                    }
                    val cleanDigits = recipPhone.replace(Regex("\\D"), "")
                    recipientsList.add(
                        mapOf(
                            "phone" to recipPhone,
                            "name" to (contactName ?: recipPhone),
                            "cleanDigits" to cleanDigits
                        )
                    )
                }
                groupTitle = recipientsList.joinToString(", ") { it["name"] ?: it["phone"] ?: "" }
            } else if (convMeta != null && convMeta.recipients.isNotEmpty()) {
                isGroup = false
                rawAddress = convMeta.recipients.first()
            } else {
                rawAddress = threadAddressesFallback[threadId] ?: combinedMessages.firstOrNull()?.address ?: "Unknown"
                isGroup = false
            }

            val contactName = if (!isGroup) {
                contactNameCache.getOrPut(rawAddress) {
                    resolveContactName(context, rawAddress)
                }
            } else {
                groupTitle
            }

            val latestMessage = combinedMessages.lastOrNull()
            val lastTimestamp = latestMessage?.timestamp
                ?: convMeta?.date
                ?: System.currentTimeMillis()
            val snippet = latestMessage?.body
                ?: convMeta?.snippet
                ?: ""

            // Build map of messages for Firebase RTDB with contact names resolved
            val messagesMap = combinedMessages.associate { msg ->
                val msgContact = if (msg.isIncoming) {
                    contactNameCache.getOrPut(msg.address) {
                        resolveContactName(context, msg.address)
                    }
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

        val sorted = resultThreads.sortedByDescending { it.lastTimestamp }
        return if (sorted.size > maxThreads) sorted.take(maxThreads) else sorted
    }

    /**
     * Reads conversations metadata from `content://mms-sms/conversations?simple=true`.
     * Maps thread recipient IDs to actual phone numbers via canonical addresses.
     */
    private fun queryConversationMetadata(
        context: Context,
        canonicalAddressCache: MutableMap<String, String>
    ): Map<Long, ConversationMetadata> {
        val map = mutableMapOf<Long, ConversationMetadata>()
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

        return map
    }

    /**
     * Resolves a Telephony recipient_id to a phone number via canonical addresses.
     */
    private fun resolveCanonicalAddress(context: Context, recipientId: String): String? {
        val uri = Uri.parse("content://mms-sms/canonical-address/$recipientId")
        return try {
            val cursor = context.contentResolver.query(uri, null, null, null, null)
            cursor?.use { c ->
                if (c.moveToFirst()) {
                    c.getString(0)
                } else null
            }
        } catch (e: Exception) {
            Log.d(TAG, "Failed resolving canonical address for $recipientId: ${e.message}")
            null
        }
    }

    /**
     * Queries MMS messages for rich group chat text bodies and senders.
     */
    private fun queryMmsMessages(
        context: Context,
        maxMessagesPerThread: Int
    ): Map<Long, List<SmsMessageItem>> {
        val mmsMap = mutableMapOf<Long, MutableList<SmsMessageItem>>()
        val uri = Uri.parse("content://mms")

        try {
            val projection = arrayOf("_id", "thread_id", "date", "msg_box", "read")
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

                while (c.moveToNext()) {
                    if (idCol == -1 || threadIdCol == -1) break
                    val mmsId = c.getLong(idCol)
                    val threadId = c.getLong(threadIdCol)
                    val list = mmsMap.getOrPut(threadId) { mutableListOf() }
                    if (list.size >= maxMessagesPerThread) continue

                    // Telephony MMS date is in seconds
                    val rawDate = if (dateCol != -1) c.getLong(dateCol) else 0L
                    val timestamp = if (rawDate < 10000000000L) rawDate * 1000L else rawDate
                    val msgBox = if (msgBoxCol != -1) c.getInt(msgBoxCol) else 1
                    val read = if (readCol != -1) c.getInt(readCol) == 1 else true
                    val isIncoming = msgBox == 1 // 1 = inbox, 2 = sent

                    val mmsContent = getMmsContent(context, mmsId)
                    val sender = if (isIncoming) getMmsSender(context, mmsId) ?: "Group Member" else "Me"

                    list.add(
                        SmsMessageItem(
                            id = "mms_$mmsId",
                            threadId = threadId,
                            address = sender,
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
                val dataCol = c.getColumnIndex("_data")

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
     * Retrieves the sender phone number for an incoming MMS.
     */
    private fun getMmsSender(context: Context, mmsId: Long): String? {
        val uri = Uri.parse("content://mms/$mmsId/addr")
        val selection = "type = $MMS_ADDR_TYPE_FROM"

        return try {
            val cursor = context.contentResolver.query(uri, arrayOf("address"), selection, null, null)
            cursor?.use { c ->
                if (c.moveToFirst()) {
                    c.getString(0)
                } else null
            }
        } catch (e: Exception) {
            null
        }
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

    private data class ConversationMetadata(
        val threadId: Long,
        val date: Long,
        val messageCount: Int,
        val recipients: List<String>,
        val snippet: String,
        val unreadCount: Int
    )
}
