# PushBridge v2.5

> **Self-Hosted Cross-Platform Notification Mirroring, Threaded SMS & Group Chat Bridge**  
> A private, modular alternative to Pushbullet connecting Google Chrome, Android devices, and a fallback cloud sync relay using Firebase as the real-time synchronization backend.

---

## What's New in Version 2.5

1. **Contact Resolution & Identity Mapping in SMS Threads**:
   - Cross-references incoming and existing phone numbers with Google Contacts and the local device telephony contact store.
   - Automatically resolves numbers to contact display names, photos, and initials in both the thread list and conversation bubbles.
   - Gracefully falls back to formatted readable telephone numbers (e.g. `+1 (555) 234-5678`) when unmapped.

2. **Multi-Recipient Group Chat Support**:
   - Android Companion app parses multi-recipient group conversations via `content://mms-sms/conversations?simple=true` and resolves canonical addresses (`content://mms-sms/canonical-address/{id}`).
   - Registered `MmsReceiver` for `WAP_PUSH_DELIVER` / `WAP_PUSH_RECEIVED` and a debounced `ContentObserver` on `content://mms-sms/` for automatic group message detection.
   - Extension UI displays group icons (`👥`), group titles, participant counts, interactive participant chips, and broadcasts outbound messages to all recipients in the thread.

3. **Robust Contact Autocomplete with Full Keyboard Navigation**:
   - Unified search across Google Contacts (People API) and synced device contacts.
   - Keyboard accessible (`ArrowDown`, `ArrowUp`, `Enter`, `Escape`) with active highlight and automatic scrolling.
   - Integrated into both the sidebar search bar and the "New Message" compose recipient bar.

4. **Automatic Thread Synchronization & Real-Time Device Presence**:
   - Device presence heartbeat and connection status monitor via Firebase RTDB (`.info/connected` and `devices/{deviceId}/presence`).
   - Android companions automatically maintain `online: true`, `lastHeartbeat`, and disconnect hooks (`onDisconnect`).
   - Chrome background service worker detects when the phone transitions to **Online** and automatically synchronizes active SMS threads and contacts without requiring manual user polling.
   - When the phone drops offline, the extension displays a `🔴 Phone Offline` status badge and an offline banner, safely queuing outbound messages until reconnected.

---

## Workspace Structure

```
pushbullet-bridge/
├── extension/          # Google Chrome Extension (Manifest V3 - v2.5.0)
│   ├── manifest.json   # MV3 config with identity, notifications, storage, alarms
│   ├── background.js   # Service worker: Firebase SSE stream, device presence, auto-sync
│   ├── firebase-config.js # Firebase Realtime Database configuration
│   ├── contacts/
│   │   └── google-contacts.js # People API OAuth2, device contact cache, identity resolution
│   ├── popup/
│   │   ├── popup.html  # Split-pane SMS/group chat, presence badges, bundled notifications
│   │   ├── popup.js    # Thread controller, group chat renderer, autocomplete, presence
│   │   └── tailwind.css# Standalone Tailwind CSS v4 styling
│   └── icons/          # Multi-resolution icon set (16px, 48px, 128px)
│
├── android-app/        # Native Kotlin Android Companion App
│   ├── app/src/main/
│   │   ├── AndroidManifest.xml # SMS, MMS, WAP_PUSH, and Notification listener setup
│   │   └── java/com/example/pushbridge/
│   │       ├── MainActivity.kt        # Edge-to-edge Compose container & permission flows
│   │       ├── ui/main/MainScreen.kt  # Bridge status dashboard & thread sync triggers
│   │       ├── service/               # NotificationListenerService with Telephony ContentObserver
│   │       ├── receiver/              # SmsReceiver and MmsReceiver
│   │       ├── data/
│   │       │   ├── SmsRepository.kt   # Group MMS detection, canonical addresses & contacts
│   │       │   ├── PushBridgeFirebaseManager.kt # Presence monitor, group broadcast engine
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

## Installation & Setup Instructions

### 1. Google Chrome Extension (Manifest V3)

1. Open Google Chrome and navigate to:
   ```
   chrome://extensions/
   ```
2. Enable **Developer mode** toggle in the top-right corner.
3. Click the **Load unpacked** button.
4. Select the `extension/` directory from this project workspace:
   ```
   /Users/volliver/.gemini/antigravity/scratch/pushbullet-bridge/extension
   ```
5. The **PushBridge - Notification & SMS Sync (v2.5.0)** extension will now appear in your toolbar.
6. Click the extension puzzle icon and pin **PushBridge** for instant access.
7. Open the popup:
   - Click **Google Contacts** in the header to authenticate with Google People API.
   - The device status badge will show `🟢 Phone Online` once your Android companion connects.

---

### 2. Android Companion App

#### Prerequisites:
- Android device running Android 8.0 (API 26) or higher.
- Developer Options & USB Debugging enabled on your phone.
- USB cable or Wireless ADB pairing.

#### Build & Install:
From the root workspace directory, run:
```bash
cd android-app
./gradlew assembleDebug
```

The APK will be generated at:
```
android-app/app/build/outputs/apk/debug/app-debug.apk
```

Install to your connected device using `adb`:
```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

#### First-Time Phone Permissions Setup:
1. Open **PushBridge** on your phone.
2. Grant runtime permissions when prompted:
   - **Notification Access**: Grants `PushBridge Notification Interceptor` permission to read notifications and execute native RemoteInput replies.
   - **SMS & Contacts Permissions**: Tap **"Grant SMS Permissions"** to allow `READ_SMS`, `SEND_SMS`, `RECEIVE_MMS`, and `READ_CONTACTS`.
3. Tap **"Sync Full SMS Threads to Firebase"**.
4. The background presence engine will mark the device `ONLINE` and automatically synchronize all conversation threads, group chats, and address book identities to the Chrome Extension.

---

### 3. Optional Local Express Relay Server

For local fallback HTTP sync without connecting directly through the browser:
```bash
cd relay-server
npm install
npm start
```
The relay server will run on port `5050`.
