package com.example.pushbridge.model

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
    val timestamp: Long = System.currentTimeMillis()
) {
    fun toMap(): Map<String, Any?> = mapOf(
        "id" to id,
        "sender" to sender,
        "body" to body,
        "timestamp" to timestamp,
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
    val isRead: Boolean = true
) {
    fun toMap(): Map<String, Any?> = mapOf(
        "id" to id,
        "threadId" to threadId,
        "address" to address,
        "contactName" to (contactName ?: ""),
        "body" to body,
        "timestamp" to timestamp,
        "isIncoming" to isIncoming,
        "isRead" to isRead
    )
}

data class SmsConversationThread(
    val threadId: Long = 0L,
    val address: String = "",
    val contactName: String? = null,
    val snippet: String = "",
    val lastTimestamp: Long = System.currentTimeMillis(),
    val unreadCount: Int = 0,
    val messages: Map<String, Any?> = emptyMap()
) {
    fun toMap(): Map<String, Any?> = mapOf(
        "threadId" to threadId,
        "address" to address,
        "contactName" to (contactName ?: ""),
        "snippet" to snippet,
        "lastTimestamp" to lastTimestamp,
        "unreadCount" to unreadCount,
        "messages" to messages
    )
}

data class InboundCommand(
    val commandId: String = "",
    val type: String = "", // "REMOTE_REPLY", "SEND_SMS", "SYNC_THREADS"
    val threadId: Long? = null,
    val notificationKey: String? = null,
    val replyText: String? = null,
    val recipient: String? = null,
    val message: String? = null,
    val timestamp: Long = System.currentTimeMillis(),
    val status: String = "pending",
    val result: String? = null
)
