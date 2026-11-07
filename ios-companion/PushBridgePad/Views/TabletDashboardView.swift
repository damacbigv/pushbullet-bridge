//
//  TabletDashboardView.swift
//  PushBridgePad (iPadOS Companion Stub)
//
//  SwiftUI Dual-Column Split-View for iPad with in-memory search, pagination, and media previews
//

import SwiftUI

public struct TabletDashboardView: View {
    @StateObject private var relay = FirebaseRelayManager.shared
    @State private var selectedThread: SmsConversationThread? = nil
    @State private var searchQuery: String = ""
    @State private var composeText: String = ""
    @State private var selectedLimit: Int = 30
    @State private var showLightbox: Bool = false
    @State private var lightboxImageUri: String? = nil
    
    public init() {}
    
    public var body: some View {
        NavigationSplitView {
            // LEFT SIDEBAR: Threads, Search Bar & Pagination
            VStack(spacing: 0) {
                // In-Memory Fast Search Bar
                HStack {
                    Image(systemName: "magnifyingglass")
                        .foregroundColor(.gray)
                    TextField("Search contacts, names or SMS...", text: $searchQuery)
                        .textFieldStyle(.plain)
                        .font(.system(size: 14))
                    if !searchQuery.isEmpty {
                        Button(action: { searchQuery = "" }) {
                            Image(systemName: "xmark.circle.fill")
                                .foregroundColor(.gray)
                        }
                    }
                }
                .padding(10)
                .background(Color(.secondarySystemBackground))
                .cornerRadius(10)
                .padding(.horizontal, 12)
                .padding(.top, 10)
                
                // Pagination Limits Segmented Bar (30 / 50 / 100)
                Picker("Pagination Limit", selection: $selectedLimit) {
                    Text("30 Threads").tag(30)
                    Text("50 Threads").tag(50)
                    Text("100 Threads").tag(100)
                }
                .pickerStyle(.segmented)
                .padding(.horizontal, 12)
                .padding(.vertical, 8)
                
                // Thread List
                List(filteredThreads, selection: $selectedThread) { thread in
                    ThreadRow(thread: thread, highlightQuery: searchQuery)
                        .tag(thread)
                }
                .listStyle(.sidebar)
                
                // Sidebar Footer Status
                HStack {
                    Circle()
                        .fill(relay.primaryPhoneOnline ? Color.green : Color.red)
                        .frame(width: 8, height: 8)
                    Text(relay.primaryPhoneOnline ? "Phone Online" : "Phone Offline (Queued)")
                        .font(.caption2)
                        .foregroundColor(.secondary)
                    Spacer()
                    Text("\(filteredThreads.count) conversations")
                        .font(.caption2)
                        .foregroundColor(.secondary)
                }
                .padding(10)
                .background(Color(.systemBackground))
            }
            .navigationTitle("PushBridge Pad")
        } detail: {
            // RIGHT DETAIL: Active Conversation, Bubbles & Composer
            if let thread = selectedThread {
                ActiveConversationView(
                    thread: thread,
                    highlightQuery: searchQuery,
                    onImageClicked: { uri in
                        lightboxImageUri = uri
                        showLightbox = true
                    }
                )
            } else {
                VStack(spacing: 12) {
                    Image(systemName: "bubble.left.and.bubble.right")
                        .font(.system(size: 48))
                        .foregroundColor(.secondary)
                    Text("Select a conversation")
                        .font(.headline)
                    Text("Or type in the search bar to query all loaded message bodies.")
                        .font(.subheadline)
                        .foregroundColor(.secondary)
                }
            }
        }
        .onAppear {
            relay.connect()
        }
        .sheet(isPresented: $showLightbox) {
            if let uri = lightboxImageUri {
                MediaLightboxView(imageUri: uri)
            }
        }
    }
    
    private var filteredThreads: [SmsConversationThread] {
        if searchQuery.isEmpty {
            return relay.threads
        }
        let q = searchQuery.lowercased()
        return relay.threads.filter { thread in
            let matchContact = thread.contactName?.lowercased().contains(q) ?? false
            let matchPhone = thread.address.lowercased().contains(q)
            let matchSnippet = thread.snippet.lowercased().contains(q)
            let matchMessages = thread.messages?.values.contains { $0.body.lowercased().contains(q) } ?? false
            return matchContact || matchPhone || matchSnippet || matchMessages
        }
    }
}

struct ThreadRow: View {
    let thread: SmsConversationThread
    let highlightQuery: String
    
    var body: some View {
        HStack(spacing: 12) {
            Circle()
                .fill(LinearGradient(colors: [.blue, .purple], startPoint: .topLeading, endPoint: .bottomTrailing))
                .frame(width: 40, height: 40)
                .overlay(
                    Text(thread.isGroup ? "👥" : String(thread.contactName?.prefix(2) ?? "SMS"))
                        .font(.caption)
                        .fontWeight(.bold)
                        .foregroundColor(.white)
                )
            
            VStack(alignment: .leading, spacing: 3) {
                HStack {
                    Text(thread.contactName ?? thread.address)
                        .font(.system(size: 14, weight: .semibold))
                        .lineLimit(1)
                    Spacer()
                    let timeStr = formatThreadListTimestamp(thread.lastTimestamp)
                    Text(timeStr)
                        .font(.system(size: 11, design: .monospaced))
                        .foregroundColor(.secondary)
                    if thread.isGroup {
                        Text("Group")
                            .font(.system(size: 9, weight: .bold))
                            .padding(.horizontal, 4)
                            .padding(.vertical, 1)
                            .background(Color.purple.opacity(0.2))
                            .foregroundColor(.purple)
                            .cornerRadius(4)
                    }
                }
                Text(thread.snippet)
                    .font(.system(size: 12))
                    .foregroundColor(.secondary)
                    .lineLimit(1)
            }
        }
        .padding(.vertical, 4)
    }
}

struct ActiveConversationView: View {
    let thread: SmsConversationThread
    let highlightQuery: String
    let onImageClicked: (String) -> Void
    @State private var inputText: String = ""
    
    var body: some View {
        VStack(spacing: 0) {
            // Header
            HStack {
                Text(thread.contactName ?? thread.address)
                    .font(.headline)
                Spacer()
                Text(thread.isGroup ? "Group Broadcast" : "1-on-1 SMS")
                    .font(.caption)
                    .foregroundColor(.secondary)
            }
            .padding()
            .background(Color(.systemBackground))
            
            Divider()
            
            // Messages Scroll
            ScrollView {
                LazyVStack(spacing: 12) {
                    if let msgs = thread.messages?.values.sorted(by: { $0.timestamp < $1.timestamp }) {
                        ForEach(msgs) { msg in
                            MessageBubble(msg: msg, onImageClicked: onImageClicked)
                        }
                    }
                }
                .padding()
            }
            
            Divider()
            
            // Composer
            HStack {
                TextField("Type a message (Press return to send)...", text: $inputText)
                    .textFieldStyle(.roundedBorder)
                Button("Send") {
                    if !inputText.isEmpty {
                        FirebaseRelayManager.shared.sendChatMessage(
                            recipient: thread.address,
                            message: inputText,
                            isGroup: thread.isGroup,
                            threadId: thread.threadId
                        ) { _ in
                            inputText = ""
                        }
                    }
                }
                .buttonStyle(.borderedProminent)
            }
            .padding()
            .background(Color(.systemBackground))
        }
    }
}

struct MessageBubble: View {
    let msg: SmsMessageItem
    let onImageClicked: (String) -> Void
    
    var body: some View {
        HStack {
            if !msg.isIncoming { Spacer() }
            
            VStack(alignment: msg.isIncoming ? .leading : .trailing, spacing: 4) {
                // Optional Inline Image Attachment
                if let attachmentData = msg.attachmentData, !attachmentData.isEmpty {
                    Image(systemName: "photo")
                        .resizable()
                        .scaledToFit()
                        .frame(maxHeight: 180)
                        .cornerRadius(12)
                        .onTapGesture {
                            onImageClicked(attachmentData)
                        }
                }
                
                Text(msg.body)
                    .padding(10)
                    .background(msg.isIncoming ? Color(.secondarySystemBackground) : Color.blue)
                    .foregroundColor(msg.isIncoming ? .primary : .white)
                    .cornerRadius(14)
            }
            
            if msg.isIncoming { Spacer() }
        }
    }
}

struct MediaLightboxView: View {
    let imageUri: String
    @Environment(\.dismiss) var dismiss
    
    var body: some View {
        VStack {
            HStack {
                Spacer()
                Button("Done") { dismiss() }
                    .padding()
            }
            Spacer()
            Text("Full-Screen Media Lightbox Preview")
                .foregroundColor(.secondary)
            Spacer()
        }
    }
}

/**
 Context-Aware Thread List Timestamp Formatter (Today: time only, Older: day of week + month + year)
 */
func formatThreadListTimestamp(_ timestamp: Int64) -> String {
    guard timestamp > 0 else { return "" }
    let date = Date(timeIntervalSince1970: TimeInterval(timestamp) / 1000.0)
    let isToday = Calendar.current.isDateInToday(date)
    
    let displayFormatter = DateFormatter()
    if isToday {
        displayFormatter.timeStyle = .short
        displayFormatter.dateStyle = .none
    } else {
        displayFormatter.dateFormat = "EEE, MMM yyyy"
    }
    
    return displayFormatter.string(from: date)
}
