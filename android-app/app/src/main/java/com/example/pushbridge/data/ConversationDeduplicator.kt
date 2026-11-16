package com.example.pushbridge.data

import com.example.pushbridge.model.SmsConversationThread
import com.example.pushbridge.model.SmsMessageItem
import java.util.Locale
import java.util.regex.Pattern

/**
 * Message & Thread Normalization/Deduplication Engine.
 *
 * Normalizes contact identifiers, filters self/carrier routing artifacts,
 * consolidates fragmented SMS, MMS, and RCS conversation threads,
 * and deduplicates individual message bubbles using a compound fingerprint
 * (normalized_sender, timestamp_window_±5s, message_body_hash).
 */
object ConversationDeduplicator {

    private val ZERO_WIDTH_REGEX = Regex("[\\u200B\\u200C\\u200D\\u200E\\u200F\\uFEFF]")
    private val WHITESPACE_REGEX = Regex("\\s+")
    private val MESSAGE_COUNT_SUFFIX = Regex("\\s*\\(\\d+\\s+messages?\\)\\s*$", RegexOption.IGNORE_CASE)

    /**
     * Extracts the last 10 digits of a phone number for canonical comparison.
     */
    fun normalizeDigits(phone: String?): String {
        if (phone.isNullOrBlank()) return ""
        val digits = phone.replace(Regex("\\D"), "")
        return if (digits.length > 10) digits.takeLast(10) else digits
    }

    /**
     * Checks if an address/token is the local user's self-identifier (email/phone)
     * or a carrier MMS gateway routing artifact (e.g. +444332222235).
     */
    fun isSelfOrCarrierToken(raw: String?, selfPhoneDigits: String? = null): Boolean {
        if (raw.isNullOrBlank()) return true
        val trimmed = raw.trim().lowercase(Locale.ROOT)

        // Email address (e.g. volliver@gmail.com attached to MMS PDU headers)
        if (trimmed.contains("@")) return true

        // Android telephony placeholder tokens
        if (trimmed.contains("insert-address-token") || trimmed.startsWith("mailto:")) return true

        val digits = trimmed.replace(Regex("\\D"), "")

        // Carrier MMS gateway or routing synthetic numbers (e.g. +44433..., 444332222235, or > 11 digits)
        if (digits.startsWith("44433") || digits.length > 11) return true

        // User's own registered/cellular line number if provided
        if (!selfPhoneDigits.isNullOrBlank()) {
            val selfNorm = normalizeDigits(selfPhoneDigits)
            if (selfNorm.isNotBlank() && digits.endsWith(selfNorm)) return true
        }

        return false
    }

    /**
     * Cleans a conversation or group title by stripping lock symbols and "(XX messages)" counters.
     */
    fun cleanTitle(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        return raw.replace("🔒", "")
            .replace(MESSAGE_COUNT_SUFFIX, "")
            .trim()
    }

    /**
     * Extracts non-self, non-carrier participant phone digits (10 digits) from a thread.
     */
    fun extractNonSelfParticipants(
        thread: SmsConversationThread,
        selfPhoneDigits: String? = null
    ): Set<String> {
        val result = mutableSetOf<String>()

        // 1. Extract from recipients list
        for (r in thread.recipients) {
            val phone = r["phone"] ?: r["cleanDigits"] ?: ""
            if (!isSelfOrCarrierToken(phone, selfPhoneDigits)) {
                val d = normalizeDigits(phone)
                if (d.length >= 7) result.add(d)
            }
        }

        // 2. Extract from raw address string (may be comma-delimited)
        val parts = thread.address.split(",")
        for (part in parts) {
            if (!isSelfOrCarrierToken(part, selfPhoneDigits)) {
                val d = normalizeDigits(part)
                if (d.length >= 7) result.add(d)
            }
        }

        return result
    }

    /**
     * Discovers genuine user-defined group titles and maps them to their known participants.
     */
    fun buildNamedGroupMap(
        threads: List<SmsConversationThread>,
        selfPhoneDigits: String? = null
    ): Map<String, Set<String>> {
        val map = mutableMapOf<String, MutableSet<String>>()

        for (t in threads) {
            val title = cleanTitle(t.groupTitle ?: "")
            if (title.isBlank()) continue

            // A genuine group title does not contain commas, &, @, and is not a plain phone number
            val isCustomName = !title.contains(",") &&
                    !title.contains("&") &&
                    !title.contains("@") &&
                    !title.startsWith("+") &&
                    !title.all { it.isDigit() || it in setOf(' ', '-', '(', ')', '.') }

            if (isCustomName) {
                val key = title.lowercase(Locale.ROOT)
                val parts = extractNonSelfParticipants(t, selfPhoneDigits)
                if (parts.isNotEmpty()) {
                    map.getOrPut(key) { mutableSetOf() }.addAll(parts)
                }
            }
        }

        return map
    }

    /**
     * Builds a map of 1-on-1 contact display names to their verified 10-digit phone number.
     */
    fun buildContactNameToPhoneMap(threads: List<SmsConversationThread>): Map<String, String> {
        val map = mutableMapOf<String, String>()
        for (t in threads) {
            if (!t.isGroup && !t.contactName.isNullOrBlank() && !t.contactName.contains(",")) {
                val parts = extractNonSelfParticipants(t)
                if (parts.size == 1) {
                    val key = t.contactName.trim().lowercase(Locale.ROOT)
                    if (!map.containsKey(key)) {
                        map[key] = parts.first()
                    }
                }
            }
        }
        return map
    }

    /**
     * Generates a deterministic canonical thread key for deduplication and grouping.
     */
    fun generateCanonicalThreadKey(
        thread: SmsConversationThread,
        namedGroupMap: Map<String, Set<String>> = emptyMap(),
        contactNameToPhone: Map<String, String> = emptyMap(),
        selfPhoneDigits: String? = null
    ): String {
        val parts = extractNonSelfParticipants(thread, selfPhoneDigits)
        val title = cleanTitle(thread.groupTitle ?: "")

        val isCustomName = title.isNotBlank() &&
                !title.contains(",") &&
                !title.contains("&") &&
                !title.contains("@") &&
                !title.startsWith("+") &&
                !title.all { it.isDigit() || it in setOf(' ', '-', '(', ')', '.') }

        if (isCustomName) {
            return "named_group:${title.lowercase(Locale.ROOT)}"
        }

        // Check if participant set matches an established named group (e.g. Mom, Dad, Natasha -> Parent's and Wife)
        if (parts.size >= 2 && namedGroupMap.isNotEmpty()) {
            for ((groupName, groupParts) in namedGroupMap) {
                val overlap = parts.intersect(groupParts)
                if (overlap.size >= 2 && (overlap.size == groupParts.size || overlap.size == parts.size)) {
                    return "named_group:$groupName"
                }
            }
        }

        // Standard multi-member group
        if (parts.size > 1) {
            val sorted = parts.sorted().joinToString("_")
            return "group:$sorted"
        }

        // Single participant 1-on-1 thread
        if (parts.size == 1) {
            return "p2p:${parts.first()}"
        }

        // 1-on-1 Contact Name lookup fallback if address was corrupted or missing
        val cName = thread.contactName?.trim()?.lowercase(Locale.ROOT)
        if (!cName.isNullOrBlank() && !cName.contains(",") && !thread.isGroup) {
            val mappedPhone = contactNameToPhone[cName]
            if (mappedPhone != null) {
                return "p2p:$mappedPhone"
            }
        }

        // Fallback: non-number single address or thread ID
        val cleanAddr = thread.address.trim()
        if (cleanAddr.isNotBlank() && !cleanAddr.contains(",")) {
            val d = normalizeDigits(cleanAddr)
            if (d.length >= 7) return "p2p:$d"
        }

        return "tid:${thread.threadId}"
    }

    /**
     * Normalizes message body text for fuzzy deduplication:
     * collapses zero-width spaces, trim, and whitespace normalization.
     */
    fun normalizeMessageBody(body: String?): String {
        if (body.isNullOrBlank()) return ""
        return body
            .replace(ZERO_WIDTH_REGEX, "")
            .replace(WHITESPACE_REGEX, " ")
            .trim()
            .lowercase(Locale.ROOT)
    }

    /**
     * Compound fingerprint check to determine if two messages represent the same event:
     * (normalized_sender, timestamp_window_±5s, message_body_hash/content).
     */
    fun isDuplicateMessage(
        msg1: SmsMessageItem,
        msg2: SmsMessageItem,
        windowMs: Long = 5000L
    ): Boolean {
        // Timestamp window check (within ±5 seconds)
        if (Math.abs(msg1.timestamp - msg2.timestamp) > windowMs) {
            return false
        }

        // Direction / Sender check
        if (msg1.isIncoming != msg2.isIncoming) {
            return false
        }

        val sender1 = if (!msg1.isIncoming || msg1.address.equals("Me", ignoreCase = true)) {
            "me"
        } else {
            normalizeDigits(msg1.address).takeIf { it.length >= 7 }
                ?: (msg1.contactName?.lowercase(Locale.ROOT)?.trim() ?: "")
        }

        val sender2 = if (!msg2.isIncoming || msg2.address.equals("Me", ignoreCase = true)) {
            "me"
        } else {
            normalizeDigits(msg2.address).takeIf { it.length >= 7 }
                ?: (msg2.contactName?.lowercase(Locale.ROOT)?.trim() ?: "")
        }

        if (sender1 != sender2 && sender1.isNotBlank() && sender2.isNotBlank()) {
            return false
        }

        // Body comparison
        val b1 = normalizeMessageBody(msg1.body)
        val b2 = normalizeMessageBody(msg2.body)

        if (b1 == b2) return true

        // Handle substring containment for truncated notifications vs full MMS
        if (b1.length >= 10 && b2.length >= 10) {
            if (b1.contains(b2) || b2.contains(b1)) return true
        }

        return false
    }

    /**
     * Deduplicates and sorts messages chronologically, merging duplicate payloads.
     */
    fun deduplicateMessages(
        messages: Collection<SmsMessageItem>,
        windowMs: Long = 5000L
    ): List<SmsMessageItem> {
        val sorted = messages.sortedBy { it.timestamp }
        val deduped = mutableListOf<SmsMessageItem>()

        for (msg in sorted) {
            var matchedIdx = -1
            for (i in deduped.indices.reversed()) {
                val prev = deduped[i]
                if (Math.abs(msg.timestamp - prev.timestamp) > windowMs + 1000L) {
                    if (msg.timestamp - prev.timestamp > windowMs + 1000L) {
                        break
                    }
                }
                if (isDuplicateMessage(msg, prev, windowMs)) {
                    matchedIdx = i
                    break
                }
            }

            if (matchedIdx >= 0) {
                // Merge duplicate into existing entry, retaining richer data
                val prev = deduped[matchedIdx]
                val isRcs = prev.isRcs || msg.isRcs
                val isEncrypted = prev.isEncrypted || msg.isEncrypted
                val protocol = if (isEncrypted) "E2EE_RCS" else if (isRcs) "RCS" else prev.protocol

                val bestBody = if (msg.body.length > prev.body.length) msg.body else prev.body
                val bestAttachData = prev.attachmentData ?: msg.attachmentData
                val bestAttachMime = prev.attachmentMime ?: msg.attachmentMime

                deduped[matchedIdx] = prev.copy(
                    body = bestBody,
                    attachmentData = bestAttachData,
                    attachmentMime = bestAttachMime,
                    isRcs = isRcs,
                    isEncrypted = isEncrypted,
                    protocol = protocol,
                    isRead = prev.isRead || msg.isRead
                )
            } else {
                deduped.add(msg)
            }
        }

        return deduped
    }

    /**
     * Consolidates duplicate and overlapping conversation threads into unified threads.
     */
    fun deduplicateAndMergeThreads(
        threads: List<SmsConversationThread>,
        selfPhoneDigits: String? = null
    ): List<SmsConversationThread> {
        if (threads.isEmpty()) return emptyList()

        // 1. Build map of genuine named groups and contact lookup maps
        val namedGroupMap = buildNamedGroupMap(threads, selfPhoneDigits)
        val contactNameToPhone = buildContactNameToPhoneMap(threads)

        // 2. Group all threads by canonical key
        val grouped = mutableMapOf<String, MutableList<SmsConversationThread>>()
        for (t in threads) {
            val key = generateCanonicalThreadKey(t, namedGroupMap, contactNameToPhone, selfPhoneDigits)
            grouped.getOrPut(key) { mutableListOf() }.add(t)
        }

        val result = mutableListOf<SmsConversationThread>()

        // 3. Merge each group into a single cohesive conversation
        for ((_, threadGroup) in grouped) {
            if (threadGroup.size == 1) {
                // Sanitize single thread to ensure self/carrier items aren't masquerading as groups
                val single = threadGroup.first()
                val nonSelfParts = extractNonSelfParticipants(single, selfPhoneDigits)

                if (single.isGroup && nonSelfParts.size <= 1) {
                    // Turn erroneously flagged group (e.g. Natasha + volliver@gmail.com) into 1-on-1 thread
                    val cleanRecip = single.recipients.filterNot { isSelfOrCarrierToken(it["phone"], selfPhoneDigits) }
                    val cleanTitle = cleanRecip.firstOrNull()?.get("name")
                        ?: cleanRecip.firstOrNull()?.get("phone")
                        ?: single.contactName?.substringBefore(",")?.trim()
                        ?: single.address.substringBefore(",").trim()

                    val cleanAddr = cleanRecip.firstOrNull()?.get("phone") ?: single.address.substringBefore(",").trim()
                    result.add(
                        single.copy(
                            isGroup = false,
                            groupTitle = null,
                            contactName = cleanTitle,
                            address = cleanAddr,
                            recipients = cleanRecip
                        )
                    )
                } else {
                    result.add(single)
                }
                continue
            }

            // Multiple threads exist for the exact same contact or group -> Merge them!
            // Stable threadId: choose the lowest positive telephony ID
            val stableThreadId = threadGroup
                .map { it.threadId }
                .filter { it in 1L..7999999999L }
                .minOrNull()
                ?: threadGroup.map { it.threadId }.minOrNull()
                ?: 0L

            // Highest protocol
            val isEncrypted = threadGroup.any { it.isEncrypted || it.protocol == "E2EE_RCS" }
            val isRcs = threadGroup.any { it.isRcs || it.protocol == "RCS" || isEncrypted }
            val protocol = if (isEncrypted) "E2EE_RCS" else if (isRcs) "RCS" else "SMS"

            // Aggregate participants
            val nonSelfParts = mutableSetOf<String>()
            val combinedRecipients = mutableListOf<Map<String, String>>()
            val seenRecipPhones = mutableSetOf<String>()

            for (t in threadGroup) {
                nonSelfParts.addAll(extractNonSelfParticipants(t, selfPhoneDigits))
                for (r in t.recipients) {
                    val p = r["phone"] ?: ""
                    if (!isSelfOrCarrierToken(p, selfPhoneDigits)) {
                        val d = normalizeDigits(p)
                        if (d.length >= 7 && seenRecipPhones.add(d)) {
                            combinedRecipients.add(r)
                        }
                    }
                }
            }

            // Determine if true group
            val isTrueGroup = nonSelfParts.size > 1

            // Best group title or contact name
            val genuineTitle = threadGroup.mapNotNull { cleanTitle(it.groupTitle) }.firstOrNull {
                it.isNotBlank() && !it.contains(",") && !it.contains("&") && !it.contains("@") &&
                        !it.startsWith("+") && !it.all { c -> c.isDigit() || c in setOf(' ', '-', '(', ')', '.') }
            }

            val finalGroupTitle = if (isTrueGroup) {
                genuineTitle ?: combinedRecipients.joinToString(", ") { it["name"] ?: it["phone"] ?: "" }.takeIf { it.isNotBlank() }
            } else null

            val finalContactName = if (isTrueGroup) {
                finalGroupTitle
            } else {
                threadGroup.mapNotNull { it.contactName?.takeIf { n -> n.isNotBlank() && !n.contains(",") } }
                    .firstOrNull()
                    ?: combinedRecipients.firstOrNull()?.get("name")
                    ?: threadGroup.first().address
            }

            val finalAddress = if (isTrueGroup) {
                combinedRecipients.joinToString(", ") { it["phone"] ?: "" }
            } else {
                combinedRecipients.firstOrNull()?.get("phone") ?: threadGroup.first().address.substringBefore(",")
            }

            // Collect all messages across all threads in this group
            val allMessages = mutableListOf<SmsMessageItem>()
            for (t in threadGroup) {
                allMessages.addAll(PushBridgeFirebaseManager.extractSortedMessages(t))
            }

            val dedupedMessages = deduplicateMessages(allMessages)
            val latestMessage = dedupedMessages.lastOrNull()

            val lastTimestamp = maxOf(
                latestMessage?.timestamp ?: 0L,
                threadGroup.maxOfOrNull { it.lastTimestamp } ?: 0L
            )

            val snippet = latestMessage?.body
                ?: threadGroup.map { it.snippet }.firstOrNull { it.isNotBlank() }
                ?: ""

            val unreadCount = threadGroup.maxOfOrNull { it.unreadCount } ?: 0
            val isTyping = threadGroup.any { it.isTyping }
            val typingSender = threadGroup.mapNotNull { it.typingSender }.firstOrNull()

            val messagesMap = dedupedMessages.associate { it.id to it.toMap() }

            result.add(
                SmsConversationThread(
                    threadId = stableThreadId,
                    address = finalAddress,
                    contactName = finalContactName,
                    snippet = snippet,
                    lastTimestamp = lastTimestamp,
                    unreadCount = unreadCount,
                    isGroup = isTrueGroup,
                    groupTitle = finalGroupTitle,
                    recipients = combinedRecipients,
                    messages = messagesMap,
                    isRcs = isRcs,
                    isEncrypted = isEncrypted,
                    protocol = protocol,
                    isTyping = isTyping,
                    typingSender = typingSender
                )
            )
        }

        return result.sortedByDescending { it.lastTimestamp }
    }
}
