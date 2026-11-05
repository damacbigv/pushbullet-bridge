# PushBridge v2

> **Self-Hosted Cross-Platform Notification Mirroring & SMS Bridge**  
> A private, modular alternative to Pushbullet connecting Google Chrome, Android devices, and a fallback cloud sync relay using Firebase as the real-time synchronization backend.

---

## What's New in Version 2.0

1. **Threaded SMS Chat Interface (Split-Pane Dashboard)**:
   - Modern chat application layout mirroring Pushbullet and native messaging apps.
   - Left sidebar conversation list with contact avatars, last message snippets, unread badges, and relative timestamps.
   - Right-hand active conversation view with incoming vs. outgoing message bubbles, multi-part thread grouping, and instant browser-based messaging.
   - Full SMS history and thread ID synchronization between Android Telephony and Firebase Realtime Database.
2. **Google Contacts Integration (Google People API)**:
   - Secure OAuth 2.0 authentication via `chrome.identity`.
   - Contact search and autocomplete with profile photos, formatted names, and phone numbers.
   - Automatically opens or creates conversations for selected contacts.
3. **Flexible Notification Bundling & Sorting Views**:
   - **Bundled by Source**: Hierarchically groups notifications by application (e.g., WhatsApp, Gmail, Signal, SMS, System Alerts) with collapsible headers, unread counts, and "Dismiss All from App".
   - **Chronological Unbundled**: Flat, linear timeline strictly sorted by date/time received.
   - Real-time dynamic updates as notifications arrive or are dismissed.

---

## Workspace Structure

```
pushbullet-bridge/
├── extension/          # Google Chrome Extension (Manifest V3 - v2.0.0)
│   ├── manifest.json   # MV3 config with identity, notifications, storage
│   ├── background.js   # Background service worker with Firebase SSE real-time stream
│   ├── firebase-config.js # Preconfigured Firebase Web SDK integration
│   ├── contacts/
│   │   └── google-contacts.js # Google People API OAuth2 integration & autocomplete
│   ├── popup/
│   │   ├── popup.html  # Split-pane SMS chat, bundled notifications, & clipboard UI
│   │   ├── popup.js    # Threaded chat controller, Google Contacts search, view toggles
│   │   └── tailwind.css# Standalone Tailwind CSS v4 styling
│   └── icons/          # Multi-resolution icon set (16px, 48px, 128px)
│
├── android-app/        # Native Kotlin Android Companion App
│   ├── app/src/main/
│   │   ├── AndroidManifest.xml # Interception permissions & background services
│   │   └── java/com/example/pushbridge/
│   │       ├── MainActivity.kt        # Edge-to-edge Compose container & permission flows
│   │       ├── ui/main/MainScreen.kt  # Bridge status dashboard & thread sync triggers
│   │       ├── service/               # NotificationListenerService (captures RemoteInput)
│   │       ├── receiver/              # SmsReceiver (intercepts incoming text messages)
│   │       ├── data/
│   │       │   ├── SmsRepository.kt   # Reads Telephony threads & resolves contact names
│   │       │   ├── PushBridgeFirebaseManager.kt # Firebase RTDB thread syncing engine
│   │       │   └── PushBridgeSyncManager.kt     # Fallback HTTP sync dispatcher
│   │       └── util/                  # SmsManagerHelper & NotificationReplyRegistry
│   └── build.gradle.kts
│
├── relay-server/       # Lightweight Node.js/Express Service
│   ├── src/server.js
│   └── package.json
│
├── package.json        # Workspace orchestrator scripts
└── README.md
```

---

## Quick Start Guide

### 1. Chrome Extension (v2.0.0)
1. In Chrome, navigate to `chrome://extensions/`
2. Enable **Developer mode** (top right).
3. Click **Load unpacked** and select the [`extension/`](file:///Users/volliver/.gemini/antigravity/scratch/pushbullet-bridge/extension) folder.
4. Click the **PushBridge** icon to open the dashboard (or click `↗` in the header to expand it to a full tab).

### 2. Android Companion App
```bash
cd android-app
./gradlew assembleDebug
```
Install the resulting APK on your phone (`app-debug.apk`), open the app, and tap **"Sync Full SMS Threads to Firebase"**.
