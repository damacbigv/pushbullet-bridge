# PushBridge v3.2.0 - Interactive Tablet & Android Messaging Dashboard

## Overview
PushBridge **v3.2.0** transforms the Android companion app—especially on Wi-Fi tablets like the **Samsung Galaxy Tab S7**—from a static configuration screen into a **full interactive cross-device messaging and notification dashboard** matching the Chrome extension. Alongside the new UI, v3.2.0 fixes critical live synchronization, lookup-URI phone corruption, thread fragmentation, and tablet notification flood bugs discovered in production Firebase telemetry.

---

## What's New in Version 3.2.0

### 1. Full Interactive Cross-Device Dashboard (`MainScreen.kt`, `ui/dashboard/*`)
* **Direct Launch into Messaging**: Wi-Fi tablets (`DeviceRole.WIFI_TABLET` or screen width >= `600dp`) and configured phones now land directly in an interactive 4-tab workspace (`Messages`, `Notifications`, `Clipboard`, `Settings`).
* **Responsive Tablet & Phone Navigation**: Uses an edge-to-edge `NavigationRail` on tablets (`>= 600dp`) and a bottom `NavigationBar` on compact phones, with live unread message and active notification badges.
* **Top Status Bar & Live Device Switcher**: Switch between discovered peer devices (e.g., Pixel 8 Pro and Galaxy Tab S7) with live online/offline indicators, an offline stand-by warning banner, a quick 10-theme palette picker, and a manual sync trigger.

### 2. Full-Featured SMS, MMS, Group & RCS Messaging (`MessagesTab.kt`)
* **Master-Detail Split Pane (Tablets)**: Dual-column view with conversation list (`36%`) and active chat timeline (`64%`), or stack navigation with hardware Back support on phones.
* **In-Memory Search & Highlighting**: Searches contact names, phone numbers, recipients, snippets, and loaded message bodies with instant match highlighting.
* **Scalable Pagination & New Chat Composer**: Instant `30 / 50 / 100` thread limits plus `+ New Message` with synced device contact autocomplete.
* **Rich Chat Experience**: Chronological message bubbles with date dividers, clickable URLs, decoded inline MMS/RCS image thumbnails with fullscreen tap-to-expand lightbox, group participant chips, broadcast banner, `Group` / `RCS` / `🔒 E2EE` badges, and context-aware thread timestamps (`h:mm a` today, `EEE, MMM yyyy` for older threads).
* **Optimistic Dispatches**: Sending from the tablet queues `SEND_SMS` commands to the paired Primary Phone and immediately displays optimistic outgoing bubbles.

### 3. Notifications & Clipboard Tabs (`NotificationsTab.kt`, `ClipboardTab.kt`)
* **Bundled by App & Chronological Notifications**: Collapsible per-app notification groups or flat timeline, search filtering, per-item / per-app / Clear All dismissal, and **inline Quick Reply** (`RemoteInput`) dispatched through the paired phone.
* **Universal Clipboard Sync**: Read/paste local clipboard text, push directly to Firebase (`devices/{target}/clipboard`), and copy synced clips from Chrome or peer devices in one tap.

### 4. Interactive 10-Theme Compose Engine (`Color.kt`, `Theme.kt`, `SetupTab.kt`)
* Implements all 10 high-contrast dashboard palettes identically across Chrome and Android/Tablet: *Midnight Dark, Solarized Clean, Emerald Forest, Sunset Neon, Minimalist, Cyberpunk Neon, Electric Indigo, Emerald Sunset, Crimson Velvet, and Modern Slate*, persisted via `SharedPreferences`.

### 5. Sync, Lookup-URI & Anti-Noise Fixes (`SmsRepository.kt`, `RcsConversationManager.kt`, `PushBridgeFirebaseManager.kt`, `extension/background.js`)
* **Eliminated Lookup-URI Digit Corruption**: Prevents Android `Person.uri` contact lookup URIs (`content://com.android.contacts/contacts/lookup/...`) from being stripped into bogus 60-digit phone numbers (`+333037058...`), fixing failed `SEND_SMS` commands and allowing proper Telephony thread resolution.
* **Prevented Group Summary Thread Fragmentation**: Skips `Notification.FLAG_GROUP_SUMMARY` notifications and strips trailing `(\d+ messages)` / `🔒` suffixes on both Android and Chrome extension so chats no longer split into separate threads as counts increment.
* **Type-Safe `SEND_SMS` Command Processing**: Ensures `threadId` is serialized numerically and parsed type-safely inside `try/catch` in `processCommandSnapshot` so commands never get stuck in `"processing"`.
* **Tablet Notification Noise Guard**: Adds a **"Mirror This Device's Notifications"** toggle in Settings (defaulting **OFF** on Wi-Fi tablets and **ON** on phones) and filters `CATEGORY_TRANSPORT`, `CastRCN`, and rapid 15-second burst duplicates.
* **Bandwidth-Safe Peer Discovery**: Discovers `/devices.json?shallow=true` and attaches full `/sms_threads` / `/outbound_notifications` listeners only to the actively selected peer device.

---

## Verification & Testing
* **Android Unit Tests & APK Build**: `./gradlew testDebugUnitTest assembleDebug` passed (7/7 unit tests green across `NormalizationAndFormattingTest` and `MainScreenViewModelTest`).
* **Extension Build Verification**: `node --check extension/background.js` and `npm run build:extension` (Tailwind v4 + environment config check) passed.
* **Security Audit**: Verified zero hardcoded Firebase API keys across tracked files.

---

## Downloads
* **Android App (Phone & Galaxy Tab S7)**: [`releases/PushBridge-v3.2.0.apk`](https://github.com/damacbigv/pushbullet-bridge/releases/download/v3.2.0/PushBridge-v3.2.0.apk) — *Install on both your Pixel Phone and Galaxy Tablet for complete two-way fixes.*
* **Chrome Extension Archive**: `PushBridge-Extension-v3.2.0.zip` (run `npm run build:extension` after configuring `.env` when sideloading unpacked)
* **GitHub Tag**: `v3.2.0`
