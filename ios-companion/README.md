# PushBridge iOS & iPadOS Companion Architecture (v3.0 Stub)

> **Universal Wi-Fi Tablet & iPad Companion for PushBridge**  
> Designed for iPadOS (split-view navigation, multi-windowing, and Stage Manager), this module mirrors SMS conversation threads, notifications, and group texts via Firebase Realtime Database and the PushBridge Relay Server.

---

## Architecture Overview

```
                      ┌────────────────────────────────────────┐
                      │    Primary Phone (Android SIM)         │
                      │  - Native SMS & MMS Provider           │
                      │  - Notification Interception Listener  │
                      └──────────────────┬─────────────────────┘
                                         │ WebSocket / RTDB
                                         ▼
                 ┌──────────────────────────────────────────────────┐
                 │       Firebase Realtime Database / Relay         │
                 │   `devices/{deviceId}/sms_threads`               │
                 │   `devices/{deviceId}/inbound_commands`          │
                 │   `devices/{deviceId}/outbound_notifications`    │
                 └───────────────────────┬──────────────────────────┘
                                         │
                 ┌───────────────────────┴──────────────────────────┐
                 │                                                  │
                 ▼                                                  ▼
   ┌───────────────────────────┐                      ┌───────────────────────────┐
   │ Chrome Extension (MV3)    │                      │  iPadOS Companion App     │
   │ - Desktop Split View      │                      │  - SwiftUI Dual-Pane UI   │
   │ - In-Memory Text Search   │                      │  - In-Memory Text Search  │
   │ - Media & Link Previews   │                      │  - Inline Photos & Previews│
   └───────────────────────────┘                      └───────────────────────────┘
```

## Directory Structure

- **`PushBridgePad/PushBridgePadApp.swift`**: SwiftUI Application lifecycle and Firebase/Relay initialization.
- **`PushBridgePad/Models/`**:
  - `PushBridgeModels.swift`: Codable schemas for `SmsConversationThread`, `SmsMessageItem`, `NotificationPayload`, and `InboundCommand`.
  - `DeviceRole.swift`: Device role enum distinguishing `primaryPhone`, `wifiTablet`, and `desktopClient`.
- **`PushBridgePad/Services/`**:
  - `FirebaseRelayManager.swift`: SSE/REST stream client and command dispatcher.
  - `NotificationMirrorService.swift`: Local APNs and UNUserNotificationCenter presenter.
  - `LinkPreviewService.swift`: OpenGraph parser and rich metadata cache.
- **`PushBridgePad/Views/`**:
  - `TabletDashboardView.swift`: Adaptive NavigationSplitView designed for iPad screens.
  - `ConversationSplitView.swift`: Two-column threaded conversation pane with pagination (30/50/100).
  - `MessageBubbleView.swift`: Chat bubbles with inline media thumbnails and link cards.
  - `InQuickSearchView.swift`: In-memory keyword search bar with real-time text highlight.
  - `MediaLightboxModal.swift`: Fullscreen photo modal view with zoom and save.

## Key iPadOS Features Supported

1. **NavigationSplitView Dual-Column Layout**: Left sidebar contact/thread list, right active conversation pane.
2. **In-Memory Quick Text Search**: Real-time filtering across message history without latency.
3. **Store-and-Forward Outbox**: When the primary phone is asleep, outbound messages queue in Firebase RTDB until the phone wakes.
4. **Group MMS Broadcast Routing**: Outbound group messages route through the primary phone's telephony pipeline as true broadcast group chats.
