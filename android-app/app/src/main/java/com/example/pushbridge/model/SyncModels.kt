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
    val attachmentData: String? = null
) {
    fun toMap(): Map<String, Any?> = mapOf(
        "id" to id,
        "sender" to sender,
        "body" to body,
        "timestamp" to timestamp,
        "isGroup" to isGroup,
        "recipients" to recipients,
        "attachmentData" to (attachmentData ?: ""),
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
    val attachmentData: String? = null // Base64 data URI (e.g. data:image/jpeg;base64,...)
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
        "attachmentData" to (attachmentData ?: "")
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
    val messages: Map<String, Any?> = emptyMap()
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
        "messages" to messages
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

data class PeerDeviceState(
    val deviceId: String,
    val name: String,
    val role: DeviceRole,
    val online: Boolean,
    val lastHeartbeat: Long,
    val model: String = "",
    val isPrimaryTelephony: Boolean = false
)
