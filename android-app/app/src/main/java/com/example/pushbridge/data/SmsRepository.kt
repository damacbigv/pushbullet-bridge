package com.example.pushbridge.data

import android.content.Context
import android.net.Uri
import android.provider.ContactsContract
import android.provider.Telephony
import android.util.Log
import com.example.pushbridge.model.SmsConversationThread
import com.example.pushbridge.model.SmsMessageItem
import com.example.pushbridge.util.PermissionUtils

object SmsRepository {
    private const val TAG = "SmsRepository"

    /**
     * Reads SMS conversations and thread histories from Android Telephony provider.
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

        val threadsMap = mutableMapOf<Long, MutableList<SmsMessageItem>>()
        val threadAddresses = mutableMapOf<Long, String>()
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
                    val list = threadsMap.getOrPut(threadId) { mutableListOf() }

                    // Only take up to maxMessagesPerThread per conversation
                    if (list.size >= maxMessagesPerThread) {
                        continue
                    }

                    val id = c.getString(idCol)
                    val address = c.getString(addressCol) ?: "Unknown"
                    val body = c.getString(bodyCol) ?: ""
                    val timestamp = c.getLong(dateCol)
                    val type = c.getInt(typeCol)
                    val read = c.getInt(readCol) == 1
                    val isIncoming = type == Telephony.Sms.MESSAGE_TYPE_INBOX

                    if (!threadAddresses.containsKey(threadId) && address.isNotBlank()) {
                        threadAddresses[threadId] = address
                    }

                    if (!read && isIncoming) {
                        threadUnreadCounts[threadId] = (threadUnreadCounts[threadId] ?: 0) + 1
                    }

                    list.add(
                        SmsMessageItem(
                            id = id,
                            threadId = threadId,
                            address = address,
                            contactName = null, // resolved below
                            body = body,
                            timestamp = timestamp,
                            isIncoming = isIncoming,
                            isRead = read
                        )
                    )

                    if (threadsMap.size >= maxThreads && list.size >= maxMessagesPerThread) {
                        // Stop if we have gathered sufficient threads
                        break
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error querying SMS content provider: ${e.message}", e)
        }

        // Cache resolved contact names by phone number to minimize query overhead
        val contactNameCache = mutableMapOf<String, String?>()

        return threadsMap.map { (threadId, messages) ->
            val rawAddress = threadAddresses[threadId] ?: messages.firstOrNull()?.address ?: "Unknown"
            val contactName = contactNameCache.getOrPut(rawAddress) {
                resolveContactName(context, rawAddress)
            }

            // Sort chronological (oldest to newest) for chat bubble display
            val sortedMessages = messages.sortedBy { it.timestamp }
            val latestMessage = sortedMessages.lastOrNull()
            val lastTimestamp = latestMessage?.timestamp ?: System.currentTimeMillis()
            val snippet = latestMessage?.body ?: ""

            // Build map of messages for Firebase RTDB
            val messagesMap = sortedMessages.associate { msg ->
                val msgWithContact = msg.copy(contactName = contactName)
                msg.id to msgWithContact.toMap()
            }

            SmsConversationThread(
                threadId = threadId,
                address = rawAddress,
                contactName = contactName,
                snippet = snippet,
                lastTimestamp = lastTimestamp,
                unreadCount = threadUnreadCounts[threadId] ?: 0,
                messages = messagesMap
            )
        }.sortedByDescending { it.lastTimestamp }
    }

    /**
     * Resolves phone number to Google / Device Address Book contact display name.
     */
    private fun resolveContactName(context: Context, phoneNumber: String): String? {
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
}
