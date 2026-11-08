package com.example.pushbridge.model

enum class DeviceRole {
    PRIMARY_PHONE,     // Device has cellular telephony, SMS/MMS SIM card active
    WIFI_TABLET,       // Device is a Wi-Fi only tablet (e.g. Galaxy Tab S7) pairing as a companion
    DESKTOP_EXTENSION  // Chrome extension browser client
}

data class NotificationPayload(
    val id: String,
    val notificationKey: String,
    val packageName: String,
    val appName: String,
    val title: String,
    val text: String,
    val subText: String? = null,
    val canReply: Boolean = false,
    val replyActionTitle: String? = null,
    val isMessagingApp: Boolean = false,
    val isRcs: Boolean = false,
    val isEncrypted: Boolean = false,
    val isGroup: Boolean = false,
    val conversationTitle: String? = null,
    val timestamp: Long = System.currentTimeMillis()
) {
    fun toMap(): Map<String, Any?> = mapOf(
        "id" to id,
        "notificationKey" to notificationKey,
        "packageName" to packageName,
        "appName" to appName,
        "title" to title,
        "text" to text,
        "subText" to (subText ?: ""),
        "canReply" to canReply,
        "replyActionTitle" to (replyActionTitle ?: ""),
        "isMessagingApp" to isMessagingApp,
        "isRcs" to isRcs,
        "isEncrypted" to isEncrypted,
        "isGroup" to isGroup,
        "conversationTitle" to (conversationTitle ?: ""),
        "timestamp" to timestamp
    )
}

data class SmsPayload(
    val id: String,
    val sender: String,
    val body: String,
    val timestamp: Long = System.currentTimeMillis(),
    val isGroup: Boolean = false,
    val recipients: List<String> = emptyList(),
    val attachmentData: String? = null,
    val isRcs: Boolean = false,
    val isEncrypted: Boolean = false,
    val protocol: String = if (isEncrypted) "E2EE_RCS" else if (isRcs) "RCS" else "SMS",
    val groupTitle: String? = null
) {
    fun toMap(): Map<String, Any?> = mapOf(
        "id" to id,
        "sender" to sender,
        "body" to body,
        "timestamp" to timestamp,
        "isGroup" to isGroup,
        "recipients" to recipients,
        "attachmentData" to (attachmentData ?: ""),
        "isRcs" to isRcs,
        "isEncrypted" to isEncrypted,
        "protocol" to protocol,
        "groupTitle" to (groupTitle ?: ""),
        "type" to "sms"
    )
}

data class SmsMessageItem(
    val id: String = "",
    val threadId: Long = 0L,
    val address: String = "",
    val contactName: String? = null,
    val body: String = "",
    val timestamp: Long = System.currentTimeMillis(),
    val isIncoming: Boolean = true,
    val isRead: Boolean = true,
    val attachmentUrl: String? = null,
    val attachmentMime: String? = null,
    val attachmentData: String? = null, // Base64 data URI (e.g. data:image/jpeg;base64,...)
    val isRcs: Boolean = false,
    val isEncrypted: Boolean = false,
    val protocol: String = if (isEncrypted) "E2EE_RCS" else if (isRcs) "RCS" else "SMS",
    val status: String? = null // "delivered", "read", "sent"
) {
    fun toMap(): Map<String, Any?> = mapOf(
        "id" to id,
        "threadId" to threadId,
        "address" to address,
        "contactName" to (contactName ?: ""),
        "body" to body,
        "timestamp" to timestamp,
        "isIncoming" to isIncoming,
        "isRead" to isRead,
        "attachmentUrl" to (attachmentUrl ?: ""),
        "attachmentMime" to (attachmentMime ?: ""),
        "attachmentData" to (attachmentData ?: ""),
        "isRcs" to isRcs,
        "isEncrypted" to isEncrypted,
        "protocol" to protocol,
        "status" to (status ?: "")
    )
}

data class SmsConversationThread(
    val threadId: Long = 0L,
    val address: String = "",
    val contactName: String? = null,
    val snippet: String = "",
    val lastTimestamp: Long = System.currentTimeMillis(),
    val unreadCount: Int = 0,
    val isGroup: Boolean = false,
    val groupTitle: String? = null,
    val recipients: List<Map<String, String>> = emptyList(),
    val messages: Map<String, Any?> = emptyMap(),
    val isRcs: Boolean = false,
    val isEncrypted: Boolean = false,
    val protocol: String = if (isEncrypted) "E2EE_RCS" else if (isRcs) "RCS" else "SMS",
    val isTyping: Boolean = false,
    val typingSender: String? = null
) {
    fun toMap(): Map<String, Any?> = mapOf(
        "threadId" to threadId,
        "address" to address,
        "contactName" to (contactName ?: ""),
        "snippet" to snippet,
        "lastTimestamp" to lastTimestamp,
        "unreadCount" to unreadCount,
        "isGroup" to isGroup,
        "groupTitle" to (groupTitle ?: ""),
        "recipients" to recipients,
        "messages" to messages,
        "isRcs" to isRcs,
        "isEncrypted" to isEncrypted,
        "protocol" to protocol,
        "isTyping" to isTyping,
        "typingSender" to (typingSender ?: "")
    )
}

data class DeviceContactItem(
    val id: String = "",
    val name: String = "",
    val phone: String = "",
    val cleanDigits: String = ""
) {
    fun toMap(): Map<String, Any?> = mapOf(
        "id" to id,
        "name" to name,
        "phone" to phone,
        "cleanDigits" to cleanDigits
    )
}
