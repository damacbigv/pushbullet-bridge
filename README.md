# PushBridge v3.0.0

> **Self-Hosted Cross-Platform Notification Mirroring, Scalable Threaded SMS, Group Chat Bridge & Multi-Device Tablet Companion**  
> A private, modular alternative to Pushbullet connecting Google Chrome, Android phones, Wi-Fi tablets (e.g. Samsung Galaxy Tab S7), and cloud sync relays using Firebase as the real-time synchronization backend.

[![Download Android APK](https://img.shields.io/badge/Download_APK-v3.0.0_(16_MB)-10B981?style=for-the-badge&logo=android&logoColor=white)](https://github.com/damacbigv/pushbullet-bridge/releases/download/v3.0.0/PushBridge-v3.0.0.apk)
[![GitHub Release](https://img.shields.io/badge/GitHub_Release-v3.0.0-6366F1?style=for-the-badge&logo=github&logoColor=white)](https://github.com/damacbigv/pushbullet-bridge/releases/tag/v3.0.0)
[![Chrome Extension](https://img.shields.io/badge/Chrome_Extension-Manifest_V3_(v3.0.0)-0EA5E9?style=for-the-badge&logo=googlechrome&logoColor=white)](https://github.com/damacbigv/pushbullet-bridge/tree/main/extension)

📥 **Latest Release Page**: [GitHub Release v3.0.0](https://github.com/damacbigv/pushbullet-bridge/releases/tag/v3.0.0)  
📦 **Direct APK Download**: [PushBridge-v3.0.0.apk](https://github.com/damacbigv/pushbullet-bridge/releases/download/v3.0.0/PushBridge-v3.0.0.apk)

---

## What's New in Version 3.0.0

### 1. Scalable SMS Thread Pagination (30 / 50 / 100 Threads)
- **Dynamic Limit Selector**: Instantly choose 30, 50, or 100 active conversation threads directly in the extension and tablet UI.
- **Optimized Content Provider Queries**: Android telephony content queries limit and slice MMS/SMS conversations on the device side before serialization, ensuring sub-50ms sync latencies without UI blocking or high memory consumption.
- **Persistence**: User pagination preference is automatically persisted across browser restarts and device reconnects.

### 2. True Group Message Thread Handling & Broadcast Replies
- **Multi-Recipient MMS Preservation**: Group conversations are accurately recognized via telephony canonical addresses and conversation IDs.
- **Broadcast Routing**: Outbound replies typed from the Chrome Extension or Wi-Fi tablet route through the primary phone's telephony pipeline as a unified group MMS broadcast to all participants simultaneously, preventing conversation fragmentation into isolated 1-on-1 direct messages.
- **Participant UI**: Displays group participant badges, contact chips, and broadcast notice banners in active chat timelines.

### 3. In-Memory Quick Text Search & Keyword Highlighting
- **Deep In-Memory Search**: Fast client-side search bar at the top of the SMS thread list indexes contact names, phone numbers, snippets, and all loaded message bodies.
- **Real-Time Highlighting**: Highlights matching keywords as you type with `<mark>` badges inside conversation cards and chat bubbles.
- **Interactive Feedback**: Instant search match counter with clear (`✕`) action.

### 4. Inline Photo & Media Rendering with Click-to-Expand Lightbox
- **MMS Attachment Extraction**: Automatically parses image parts (`image/*`) from `content://mms/part` and encodes optimized Base64 data URIs into the synchronized message stream.
- **Interactive Chat Thumbnails**: Image attachments render cleanly within chat bubbles with hover-to-expand controls.
- **Fullscreen Lightbox Modal**: Click-to-expand lightbox view with image zooming, caption metadata, direct download action, and keyboard `Escape` dismissal.

### 5. URL Link Preview Thumbnails (OpenGraph Engine)
- **Automatic Link Detection**: Chat bubbles detect web URLs and render clickable, secure links.
- **Rich Card Previews**: Automatically extracts OpenGraph metadata (`og:title`, `og:description`, `og:image`, `og:site_name`, domain) with caching via relay server and direct client-side fallback.

### 6. Wi-Fi Tablet Companion App & Multi-Device Sync Topology
- **Tablet Compatibility**: Manifest flags allow installation on Wi-Fi-only tablets (such as Samsung Galaxy Tab S7) by declaring `android.hardware.telephony` optional (`required="false"`).
- **Adaptive Dual-Pane Layout**: Tablet displays automatically present an edge-to-edge dual-column split view (threads on the left, active conversation on the right) on screens with width $\ge 600\,\text{dp}$.
- **Peer-Sync Topology**: Wi-Fi tablets connect directly to Firebase Realtime Database to view and manage threads, mirror notifications, and queue outbound commands through the primary phone.
- **iOS / iPad Companion Stub Architecture**: Includes a modular Swift/SwiftUI companion project architecture in `/ios-companion` designed for iPadOS.

---

## Workspace Structure

```
pushbullet-bridge/
├── extension/          # Google Chrome Extension (Manifest V3 - v3.0.0)
│   ├── manifest.json   # MV3 config with permissions & host permissions
│   ├── background.js   # Service worker: Firebase SSE stream, device presence, OpenGraph fallback
│   ├── firebase-config.js # Firebase Realtime Database configuration
│   ├── contacts/
│   │   └── google-contacts.js # People API OAuth2, contact cache, identity resolution
│   ├── popup/
│   │   ├── popup.html  # Split-pane UI, search bar, pagination buttons, media lightbox
│   │   ├── popup.js    # Thread controller, in-memory search, photo thumbnails, link previews
│   │   └── tailwind.css# Standalone Tailwind CSS v4 styling
│   └── icons/          # Multi-resolution icon set (16px, 48px, 128px)
│
├── android-app/        # Native Kotlin Android Companion App (Phone & Tablet)
│   ├── app/src/main/
│   │   ├── AndroidManifest.xml # Non-telephony tablet support & large screen declarations
│   │   └── java/com/example/pushbridge/
│   │       ├── MainActivity.kt        # Adaptive Compose container & permission flows
│   │       ├── ui/main/MainScreen.kt  # Tablet dual-pane layout & device role badges
│   │       ├── service/               # NotificationListenerService with ContentObserver
│   │       ├── receiver/              # SmsReceiver and MmsReceiver
│   │       ├── data/
│   │       │   ├── SmsRepository.kt   # MMS image downsampling, pagination & group threads
│   │       │   ├── PushBridgeFirebaseManager.kt # Peer-sync, tablet detection, command dispatch
│   │       │   └── PushBridgeSyncManager.kt     # Fallback HTTP sync dispatcher
│   │       └── model/                 # DeviceRole, PeerDeviceState, and SyncModels
│   └── build.gradle.kts
│
├── relay-server/       # Lightweight Node.js/Express Relay Service
│   ├── src/
│   │   ├── controllers/
│   │   │   ├── previewController.js  # In-memory caching OpenGraph parser
│   │   │   ├── deviceController.js   # Peer command dispatcher & device role manager
│   │   │   └── pushController.js
│   │   ├── routes/
│   │   │   ├── preview.js            # GET /api/link-preview
│   │   │   └── devices.js            # POST /api/devices/command
│   │   └── server.js
│   └── package.json
│
├── ios-companion/      # iOS / iPad Companion Architecture (SwiftUI & Swift)
│   ├── PushBridgePad/
│   │   ├── Models/PushBridgeModels.swift
│   │   ├── Services/FirebaseRelayManager.swift
│   │   └── Views/TabletDashboardView.swift
│   └── README.md
│
├── releases/           # Pre-compiled binaries & distribution packages
│   └── PushBridge-v3.0.0.apk
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
2. Enable the **Developer mode** toggle in the top-right corner.
3. Click the **Load unpacked** button.
4. Select the `extension/` directory from this workspace:
   ```
   /Users/volliver/.gemini/antigravity/scratch/pushbullet-bridge/extension
   ```
5. Pin the **PushBridge** icon to your Chrome toolbar.

### 2. Android Companion App (Phone or Wi-Fi Tablet)

1. Download **`PushBridge-v3.0.0.apk`** from [Releases](https://github.com/damacbigv/pushbullet-bridge/releases/tag/v3.0.0) or copy from `releases/PushBridge-v3.0.0.apk`.
2. On your Android phone or Samsung Galaxy Tab S7:
   - Allow installation from unknown sources if prompted.
   - Launch **PushBridge**.
   - Tap **Enable Notification Access** and allow PushBridge.
   - On the primary phone, allow SMS, Phone, and Contacts permissions.
   - On Wi-Fi-only tablets, the app detects tablet mode automatically and runs in peer-sync receiver mode.

### 3. Relay Server (Optional Local/Self-Hosted Sync)

```bash
cd relay-server
npm install
npm run start
```
Default port: `http://localhost:3000`.

---

## License

MIT License. Designed for private, self-hosted cross-platform notification and SMS synchronization.
