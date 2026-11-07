//
//  PushBridgeModels.swift
//  PushBridgePad (iPadOS Companion Stub)
//
//  Data contracts matching Android & Chrome Extension schemas
//

import Foundation

public enum DeviceRole: String, Codable {
    case primaryPhone = "PRIMARY_PHONE"
    case wifiTablet = "WIFI_TABLET"
    case desktopClient = "DESKTOP_EXTENSION"
}

public struct SmsMessageItem: Identifiable, Codable {
    public let id: String
    public let threadId: Int64
    public let address: String
    public let contactName: String?
    public let body: String
    public let timestamp: Int64
    public let isIncoming: Boolean
    public let isRead: Boolean
    public let attachmentUrl: String?
    public let attachmentMime: String?
    public let attachmentData: String? // Base64 data URI (e.g. data:image/jpeg;base64,...)
    
    public init(
        id: String,
        threadId: Int64,
        address: String,
        contactName: String? = nil,
        body: String,
        timestamp: Int64 = Int64(Date().timeIntervalSince1970 * 1000),
        isIncoming: Boolean = true,
        isRead: Boolean = true,
        attachmentUrl: String? = nil,
        attachmentMime: String? = nil,
        attachmentData: String? = nil
    ) {
        self.id = id
        self.threadId = threadId
        self.address = address
        self.contactName = contactName
        self.body = body
        self.timestamp = timestamp
        self.isIncoming = isIncoming
        self.isRead = isRead
        self.attachmentUrl = attachmentUrl
        self.attachmentMime = attachmentMime
        self.attachmentData = attachmentData
    }
}

public struct ThreadRecipient: Codable, Identifiable {
    public var id: String { phone }
    public let phone: String
    public let name: String
    public let cleanDigits: String
}

public struct SmsConversationThread: Identifiable, Codable {
    public var id: Int64 { threadId }
    public let threadId: Int64
    public let address: String
    public let contactName: String?
    public let snippet: String
    public let lastTimestamp: Int64
    public let unreadCount: Int
    public let isGroup: Boolean
    public let groupTitle: String?
    public let recipients: [ThreadRecipient]?
    public let messages: [String: SmsMessageItem]?
}

public struct NotificationPayload: Identifiable, Codable {
    public let id: String
    public let notificationKey: String
    public let packageName: String
    public let appName: String
    public let title: String
    public let text: String
    public let subText: String?
    public let canReply: Boolean
    public let replyActionTitle: String?
    public let isMessagingApp: Boolean
    public let timestamp: Int64
}

public struct InboundCommand: Codable {
    public let id: String
    public let type: String
    public let sourceDeviceId: String
    public let recipient: String?
    public let message: String?
    public let threadId: Int64?
    public let isGroup: Boolean?
    public let recipients: [String]?
    public let attachment: String?
    public let notificationKey: String?
    public let replyText: String?
    public let timestamp: Int64
    public let status: String
}
