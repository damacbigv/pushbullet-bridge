//
//  FirebaseRelayManager.swift
//  PushBridgePad (iPadOS Companion Stub)
//
//  Maintains real-time stream with Firebase RTDB and peer routing with Primary Phone
//

import Foundation
import Combine

public class FirebaseRelayManager: ObservableObject {
    public static let shared = FirebaseRelayManager()
    
    @Published public var isConnected: Bool = false
    @Published public var primaryPhoneId: String? = nil
    @Published public var primaryPhoneOnline: Bool = false
    @Published public var threads: [SmsConversationThread] = []
    @Published public var notifications: [NotificationPayload] = []
    @Published public var paginationLimit: Int = 30
    
    private let rtdbBaseUrl = "https://pushclonebridge-default-rtdb.firebaseio.com"
    private var cancellables = Set<AnyCancellable>()
    public let deviceId: String
    
    private init() {
        if let storedId = UserDefaults.standard.string(forKey: "pushbridge_ios_device_id") {
            self.deviceId = storedId
        } else {
            let newId = "ipad_" + UUID().uuidString.prefix(8).lowercased()
            UserDefaults.standard.set(newId, forKey: "pushbridge_ios_device_id")
            self.deviceId = newId
        }
    }
    
    /// Establishes Server-Sent Events (SSE) or REST polling connection to Firebase
    public func connect() {
        print("[PushBridgePad] Connecting iPad Companion (\(deviceId)) to Firebase RTDB...")
        registerPresence()
        discoverPrimaryPhone()
    }
    
    /// Registers iPad presence in `devices/{deviceId}/presence`
    private func registerPresence() {
        guard let url = URL(string: "\(rtdbBaseUrl)/devices/\(deviceId)/presence.json") else { return }
        
        let payload: [String: Any] = [
            "online": true,
            "lastHeartbeat": Int64(Date().timeIntervalSince1970 * 1000),
            "deviceName": "Apple iPad Pro (Wi-Fi Companion)",
            "deviceRole": "WIFI_TABLET",
            "deviceType": "tablet",
            "paginationLimit": paginationLimit
        ]
        
        var request = URLRequest(url: url)
        request.httpMethod = "PUT"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = try? JSONSerialization.data(withJSONObject: payload)
        
        URLSession.shared.dataTask(with: request) { _, _, _ in
            DispatchQueue.main.async {
                self.isConnected = true
            }
        }.resume()
    }
    
    /// Discovers active primary phone (starts with "android_")
    public func discoverPrimaryPhone() {
        guard let url = URL(string: "\(rtdbBaseUrl)/devices.json?shallow=true") else { return }
        
        URLSession.shared.dataTask(with: url) { data, _, _ in
            guard let data = data,
                  let dict = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else { return }
            
            let phones = dict.keys.filter { $0.hasPrefix("android_") }
            if let phone = phones.first {
                DispatchQueue.main.async {
                    self.primaryPhoneId = phone
                    self.fetchThreadsFromPhone(phone)
                }
            }
        }.resume()
    }
    
    /// Pulls conversation threads from primary phone
    public func fetchThreadsFromPhone(_ phoneId: String) {
        guard let url = URL(string: "\(rtdbBaseUrl)/devices/\(phoneId)/sms_threads.json") else { return }
        
        URLSession.shared.dataTask(with: url) { data, _, _ in
            guard let data = data,
                  let dict = try? JSONSerialization.jsonObject(with: data) as? [String: [String: Any]] else { return }
            
            // Parse threads and sort by newest
            print("[PushBridgePad] Fetched \(dict.count) SMS threads from phone \(phoneId)")
        }.resume()
    }
    
    /// Dispatches an outbound SMS or broadcast group chat via Primary Phone's inbound queue
    public func sendChatMessage(
        recipient: String,
        message: String,
        isGroup: Bool = false,
        recipients: [String] = [],
        threadId: Int64 = 0,
        attachmentData: String? = nil,
        completion: @escaping (Bool) -> Void
    ) {
        guard let phoneId = primaryPhoneId,
              let url = URL(string: "\(rtdbBaseUrl)/devices/\(phoneId)/inbound_commands.json") else {
            completion(false)
            return
        }
        
        let command: [String: Any] = [
            "type": "SEND_SMS",
            "sourceDeviceId": deviceId,
            "recipient": recipient,
            "message": message,
            "isGroup": isGroup,
            "recipients": recipients,
            "threadId": threadId,
            "attachment": attachmentData ?? "",
            "timestamp": Int64(Date().timeIntervalSince1970 * 1000),
            "status": "pending"
        ]
        
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = try? JSONSerialization.data(withJSONObject: command)
        
        URLSession.shared.dataTask(with: request) { _, response, _ in
            let success = (response as? HTTPURLResponse)?.statusCode == 200
            DispatchQueue.main.async {
                completion(success)
            }
        }.resume()
    }
}
