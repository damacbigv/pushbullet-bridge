# PushBridge v3.1.3 - Infinite Refresh Loop Elimination, Firebase Quota Hardening & Rolling Data Trimming

## Overview
PushBridge Version 3.1.3 resolves the critical stability and cloud resource issues reported in Version 3.1.0/3.1.2:
1. **Infinite UI Refresh / Render Loop**: Eliminated the rapid UI re-rendering loop that froze the Chrome extension popup across the Notifications and Threaded SMS tabs.
2. **Firebase Storage & Quota Exhaustion**: Fixed the runaway ping-pong feedback loop where incoming notifications triggered automated `SYNC_SMS_THREADS` inbound commands that repeatedly dumped 100 threads into Firebase, accumulating thousands of notifications and commands.
3. **Automated Rolling Retention Caps**: Enforced strict rolling retention ceilings in Firebase Realtime Database across all collections (30 items max for `outbound_notifications`, 15 items max for `outbound_sms`, and immediate deletion of processed `inbound_commands`).
4. **Preserved RCS & Group Chat Threads**: Resolved the issue where `syncAllSmsThreads` would wipe out active Google Messages RCS / E2EE threads (such as the family group chat *"Dad Valla Olliver II, Mom Bertha Olliver"*) by switching from destructive full replaces (`setValue`) to merge updates (`updateChildren`) backed by an in-memory RCS thread cache.
5. **CORS & Redirect Error Prevention**: Hardened `FETCH_LINK_PREVIEW` with `redirect: 'manual'` and URL shortener detection to prevent Chrome CORS errors on tracking and shortened links (`bit.ly`, `trackaipac.co`).

---

### Key Technical Implementations

#### 1. Extension Service Worker Backlog Batching (`extension/background.js`)
* **Single-Pass Backlog Processing**: Replaced the sequential iteration over `devData.outbound_notifications` in `path === '/'` with `handleInitialNotificationsBatch()`. It takes only the 30 most recent notifications, updates `chrome.storage.local` in a single write, and emits at most one `NOTIFICATION_RECEIVED` event.
* **Elimination of Automated Sync Feedback Loop**: Removed the automated `fetch(..., { type: 'SYNC_SMS_THREADS' })` invocation in `handleIncomingNotification`. Thread updates are already delivered directly from the phone and injected into local storage.
* **Removed Redundant Presence Sync**: Removed `autoSyncDeviceData()` on device online transitions, relying entirely on the native Firebase Realtime Database SSE stream.
* **CORS Safe Link Preview**: Added shortener regex filtering and set `redirect: 'manual'` on direct client fetches to avoid browser CORS errors on tracking redirects.

#### 2. Extension Popup UI Debouncing & Tab Awareness (`extension/popup/popup.js`)
* **300ms Debounce Timers**: Implemented `debouncedLoadNotifications(300)` and `debouncedLoadSmsThreads(300)` to debounce all incoming SSE broadcasts.
* **Tab-Aware DOM Rendering**: When the user is viewing the **Threaded SMS & Group Chat** tab, notification events only increment the badge counter without destroying and recreating the notification DOM tree. Similarly, when on the **Notifications** tab, thread updates only refresh the thread count badge.
* **Heartbeat Poll Throttle**: Reduced the background presence polling interval from 15 seconds to 60 seconds.

#### 3. Android Companion App Quota & Thread Protections (`PushBridgeFirebaseManager.kt` & `PushBridgeNotificationListenerService.kt`)
* **Rolling Pruning**:
  - `pruneOutboundNotifications`: Automatically trims `outbound_notifications` so it never holds more than 30 items.
  - `pruneOutboundSms`: Automatically trims `outbound_sms` so it never holds more than 15 items.
* **Immediate Inbound Command Deletion**: `updateCommandStatus` removes command nodes (`ref.removeValue()`) upon completion, ensuring `inbound_commands` remains at 0 elements.
* **RCS Thread Preservation**:
  - Added `rcsThreadsCache` in `PushBridgeFirebaseManager`.
  - Replaced `threadsRef.setValue(threadsMap)` with `threadsRef.updateChildren(threadsMap)` in `syncAllSmsThreads` so existing RCS group threads (e.g., family conversations) are never wiped out by system SMS syncs.
* **Telephony ContentObserver Consolidation**:
  - Removed 5 duplicate observers and registered a single observer on `Telephony.MmsSms.CONTENT_CONVERSATIONS_URI`.
  - Increased debounce from 800ms to 4000ms.
  - Removed secondary delayed `syncAllSmsThreads` trigger.
  - In `onNotificationPosted`, removed the redundant bulk `syncAllSmsThreads` call when an RCS message is received (retaining only `syncThreadDirectly` for the active thread).

---

### Verification & Testing
* **Firebase Database Footprint**: Verified database size reduced from >1.2 MB to <360 KB (sub-1 MB footprint, well within free Spark plan quotas).
* **Android Compilation**: `./gradlew assembleDebug` passed cleanly in 4s.
* **Extension Build**: `npm run build` compiled Tailwind CSS and validated Manifest V3 configuration without warnings.
* **Release Artifacts**:
  - `releases/PushBridge-v3.1.3.apk` (13.2 MB)
  - `releases/PushBridge-Extension-v3.1.3.zip` (1.3 MB)

---

### Artifacts & Downloads
* **Android Companion App**: [`releases/PushBridge-v3.1.3.apk`](file:///Users/volliver/.gemini/antigravity/scratch/pushbullet-bridge/releases/PushBridge-v3.1.3.apk)
* **Chrome Extension**: Version 3.1.3 (`extension/manifest.json`)
* **GitHub Tag**: `v3.1.3`
