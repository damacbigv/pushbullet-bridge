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

data class InboundCommand(
    val commandId: String = "",
    val type: String = "", // "REMOTE_REPLY" or "SEND_SMS"
    val notificationKey: String? = null,
    val replyText: String? = null,
    val recipient: String? = null,
    val message: String? = null,
    val timestamp: Long = System.currentTimeMillis(),
    val status: String = "pending",
    val result: String? = null
)
