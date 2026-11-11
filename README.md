# PushBridge v3.2.0

> **Self-Hosted Cross-Platform Notification Mirroring, Scalable Threaded SMS, RCS & E2EE Group Chat Bridge & Multi-Device Tablet Dashboard**  
> A private, modular alternative to Pushbullet connecting Google Chrome, Android phones, Wi-Fi tablets (e.g. Samsung Galaxy Tab S7), and cloud sync relays using Firebase as the real-time synchronization backend.

[![Download Android APK](https://img.shields.io/badge/Download_APK-v3.2.0_(13_MB)-10B981?style=for-the-badge&logo=android&logoColor=white)](https://github.com/damacbigv/pushbullet-bridge/releases/download/v3.2.0/PushBridge-v3.2.0.apk)
[![GitHub Release](https://img.shields.io/badge/GitHub_Release-v3.2.0-6366F1?style=for-the-badge&logo=github&logoColor=white)](https://github.com/damacbigv/pushbullet-bridge/releases/tag/v3.2.0)
[![Chrome Extension](https://img.shields.io/badge/Chrome_Extension-Manifest_V3_(v3.2.0)-0EA5E9?style=for-the-badge&logo=googlechrome&logoColor=white)](https://github.com/damacbigv/pushbullet-bridge/tree/main/extension)

📥 **Official GitHub Release Page**: [GitHub Release v3.2.0](https://github.com/damacbigv/pushbullet-bridge/releases/tag/v3.2.0)  
📦 **Direct APK Download**: [PushBridge-v3.2.0.apk](https://github.com/damacbigv/pushbullet-bridge/releases/download/v3.2.0/PushBridge-v3.2.0.apk)  
📝 **Detailed Release Notes**: [`releases/release_notes_v3.2.0.md`](file:///Users/volliver/.gemini/antigravity/scratch/pushbullet-bridge/releases/release_notes_v3.2.0.md)

---

## What's New in Version 3.2.0

### 1. Interactive Android & Galaxy Tablet Companion Dashboard
- **Full Tablet Messaging Workspace**: Launches directly into a 4-tab dashboard (`Messages`, `Notifications`, `Clipboard`, `Settings`) with a responsive left `NavigationRail` on tablets ($\ge 600\,\text{dp}$) and bottom navigation on compact phones.
- **Split-Pane SMS/MMS/RCS Chat**: Master-detail conversation list + chat timeline with optimistic message sending routed through the paired Primary Phone, `+ New Message` with synced contact autocomplete, search & highlight, `30/50/100` pagination, context-aware timestamps, group participant chips, and fullscreen tap-to-expand MMS/RCS photo lightbox.
- **Live Notifications & Universal Clipboard**: View mirrored alerts bundled by app or chronologically with inline `RemoteInput` Quick Reply and cross-device dismiss; copy/push clipboard snippets across devices.
- **Interactive 10-Theme Compose Engine**: Live switching across all 10 high-contrast themes matching the Chrome Extension (*Midnight Dark, Solarized Clean, Emerald Forest, Sunset Neon, Minimalist, Cyberpunk Neon, Electric Indigo, Emerald Sunset, Crimson Velvet, Modern Slate*).

### 2. Sync Reliability, Lookup-URI & Noise Hardening
- **Lookup-URI Sanitation**: Resolves `content://com.android.contacts/...` `Person.uri` handles via `ContactsContract` and rejects >15-digit malformed numbers (`+333037058...`), preventing failed `SEND_SMS` dispatches and enabling clean RCS-to-Telephony thread merges.
- **Group Summary Deduplication**: Filters `Notification.FLAG_GROUP_SUMMARY` and strips trailing `(\d+ messages)` suffixes on both Android and Chrome (`extension/background.js`) so active group threads no longer fragment.
- **Tablet Notification Noise Control**: Defaults **Mirror This Device's Notifications** to **OFF** on Wi-Fi tablets (configurable in Settings) and drops `CastRCN`, `CATEGORY_TRANSPORT`, and 15-second duplicate bursts.
- **Bandwidth-Safe Multi-Device Sync**: Uses shallow `/devices.json?shallow=true` discovery and binds heavy collection listeners solely to the active selected target device.

---

## Previous Highlight: Version 3.1.4 & Core Capabilities

### 1. Fixed RCS & Google Messages Group Conversation Discovery
- **Unlocked Notification Interception**: Removed the restrictive `MessagingStyle` check in `RcsConversationManager.kt`. Google Messages notifications (e.g. `"Dad Valla Olliver II, Mom Bertha Olliver: Mom Bertha Olliver"`) are now instantly recognized as active conversation threads and mapped to thread IDs $\ge 8{,}000{,}000{,}000$.
- **Direct Real-Time Thread Sync**: Intercepted RCS group messages are immediately written to Firebase (`devices/$deviceId/sms_threads/$threadId`), making family group chats appear in the Chrome extension and tablet dashboard seamlessly.
- **Merge-Safe Extension Fetching**: Clicking "Fetch from Phone" in Chrome preserves locally and remotely discovered RCS threads instead of overwriting them.

### 2. Firebase Free-Tier Storage & Bandwidth Hardening
- **Zero Inbound Ping-Pong**: Removed automated `SYNC_SMS_THREADS` command dispatch triggered by incoming messaging notifications, terminating the infinite command loop between Chrome and Android.
- **Automated Rolling Retention Caps**: Enforced strict rolling retention ceilings across collections (30 items for `outbound_notifications`, 15 items for `outbound_sms`).
- **Immediate Command Cleanup**: Android companion app deletes executed commands immediately (`ref.removeValue()`), keeping `inbound_commands` completely clean.
- **Negligible Footprint**: Total database size safely capped (<360 KB), fitting comfortably into Firebase Spark (free tier) quotas.

### 3. RCS & Family Group Thread Preservation
- **Non-Destructive Thread Merging**: Replaced `setValue()` with `updateChildren()` and an in-memory RCS cache in `PushBridgeFirebaseManager` so system SMS syncs never wipe out active Google Messages RCS or E2EE group conversations (such as *"Dad Valla Olliver II, Mom Bertha Olliver"*).
- **Consolidated Telephony Observers**: Reduced Android content observers to a single listener on `Telephony.MmsSms.CONTENT_CONVERSATIONS_URI` with a 4-second debounce.

### 4. CORS & Redirect Prevention
- Hardened link preview fetching with URL shortener detection and `redirect: 'manual'` to eliminate browser CORS errors on tracking links (`bit.ly`, `trackaipac.co`).

---

## Core Version 3.0 Capabilities

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
├── extension/          # Google Chrome Extension (Manifest V3 - v3.1.2)
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
├── android-app/        # Native Kotlin Android Companion App (Phone & Tablet - v3.1.2)
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
│   │       ├── util/                  # DateTimeUtils, SmsManagerHelper, NotificationReplyRegistry
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
│   └── PushBridge-v3.2.0.apk
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
3. Click the **Load unpacked** button (or click the **Reload** button if already installed).
4. Select the `extension/` directory from this workspace:
   ```
   /Users/volliver/.gemini/antigravity/scratch/pushbullet-bridge/extension
   ```
5. Pin the **PushBridge** icon to your Chrome toolbar.

### 2. Android Companion App (Phone or Wi-Fi Tablet)

1. Download **`PushBridge-v3.2.0.apk`** from [Releases](https://github.com/damacbigv/pushbullet-bridge/releases/tag/v3.2.0) or copy from `releases/PushBridge-v3.2.0.apk` (install on both your phone and Wi-Fi tablet).
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
