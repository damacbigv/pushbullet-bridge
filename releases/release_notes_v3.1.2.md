# PushBridge v3.1.2 - Android Sync Engine Hardening, Family Group Thread Auto-Injection & Chrome CORS/CSP Resolution

## Overview
PushBridge Version 3.1.2 resolves three critical issues identified during real-world group messaging and conversation synchronization:
1. **Android Conversation Sync Hanging**: Fixed the bug where clicking "Sync Full SMS & Group Threads" would remain indefinitely in *"Syncing Conversations..."* due to uncapped MMS table scans and unconstrained image bitmap decoding causing OutOfMemory (`OutOfMemoryError`) failures.
2. **Missing Family Group Thread Discussion**: Resolved the deficit where family group chats (e.g. *"Dad Valla Olliver II, Mom Bertha Olliver"*) appeared in the Notifications tab but failed to show up in the **Threaded SMS & Group Chat** tab.
3. **Chrome Extension CORS & CSP Violations**: Fixed `Access to fetch at 'http://bit.ly/...' blocked by CORS policy` errors by declaring `"http://*/*"` in `manifest.json` host permissions, caching resilient link preview fallbacks, and eliminating inline event handlers.

---

### Key Technical Implementations

#### 1. Android Telephony & MMS Query Hardening (`SmsRepository.kt`)
* **Bounded MMS Querying**: Added `date DESC LIMIT 150` and an explicit loop cap (`scannedMms <= 150`) to `queryMmsMessages` so the app never attempts to scan the entire lifetime MMS history of the phone.
* **Bounded SMS Querying**: Added `${Telephony.Sms.DATE} DESC LIMIT 500` and an explicit safety cap of 500 rows to `queryConversationThreads`.
* **Memory-Safe Bitmap Downsampling**: Updated `getMmsContent` to decode image bounds first (`inJustDecodeBounds = true`), calculate `inSampleSize` downsampling to at most 240×240 pixels, decode with `Bitmap.Config.RGB_565` (16-bit color, half RAM usage), compress to JPEG quality 50%, and wrap in `catch (t: Throwable)` to catch `OutOfMemoryError`.
* **Restricted Attachment Loading**: Only downsamples inline images for the 2 most recent messages per thread, keeping Firebase JSON payloads compact and sub-second fast.
* **Tokenized Contact Matching**: Updated `resolvePhoneNumberForContactName` to perform multi-stage matching: exact match, substring `LIKE`, and tokenized matching that strips family/generational titles (`Dad`, `Mom`, `Jr`, `Sr`, `II`, `III`) to match contacts like *"Valla"* or *"Bertha"* even if names in notifications contain prefixes.

#### 2. Guaranteed Async Completion & Error Recovery (`PushBridgeFirebaseManager.kt` & `MainScreen.kt`)
* **Ironclad Coroutine Handling**: Wrapped `syncAllSmsThreads` in `try { ... } catch (t: Throwable) { ... }`, guaranteeing that `withContext(Dispatchers.Main) { onComplete?.invoke(...) }` is always called to reset the UI button state.
* **Bulk Write Fallback**: If the bulk upload to `devices/$deviceId/sms_threads` encounters a network error or payload limit, it automatically falls back to writing threads individually and notifies the UI.
* **Safety Timeout**: Added a 10-second coroutine safeguard in `MainScreen.kt` so `isSyncingThreads` is always reset to `false`.

#### 3. Client-Side Messaging Notification Auto-Injection (`extension/background.js`)
* **Instant Group Thread Injection**: Introduced `injectNotificationIntoSmsThreads` into `handleIncomingNotification`. Whenever a notification arrives from Google Messages, Samsung Messages, or SMS/RCS (including notifications loaded from the backlog):
  - Parses the group title and sender (e.g. group `"Dad Valla Olliver II, Mom Bertha Olliver"` and sender `"Mom Bertha Olliver"`).
  - Automatically matches or instantiates the conversation thread in `smsThreads` in `chrome.storage.local`.
  - Appends the message with unread badges and timestamps and emits `SMS_THREADS_UPDATED`.
  - Guarantees family group threads immediately appear in the left-hand navigation list without waiting for a phone sync!

#### 4. Host Permissions & Link Preview Resiliency (`extension/manifest.json` & `extension/background.js`)
* **HTTP Host Permissions**: Added `"http://*/*"` alongside `"https://*/*"` to `manifest.json` `host_permissions` so that URL shorteners (`http://bit.ly/...`) and HTTP destinations are permitted without CORS blocking.
* **Resilient Preview Caching**: If a direct fetch fails or redirect is blocked, `FETCH_LINK_PREVIEW` creates and caches a clean domain preview card (`domain`, `url`, `title: domain`). This prevents repeated failed network requests and eliminates console spam.
* **Strict CSP Compliance**: Verified `popup.js` uses `img.addEventListener('error', ...)` without any inline `onerror` or `onclick` handlers.

---

### Verification & Testing
* **Android Compilation**: `./gradlew compileDebugKotlin` and `./gradlew assembleDebug` passed cleanly in 1s.
* **Extension Build**: `npm run build` compiled Tailwind CSS and verified Firebase credentials.
* **Release Artifact Generated**: `releases/PushBridge-v3.1.2.apk` (13.2 MB).

---

### Artifacts & Downloads
* **Android Companion App**: [`releases/PushBridge-v3.1.2.apk`](file:///Users/volliver/.gemini/antigravity/scratch/pushbullet-bridge/releases/PushBridge-v3.1.2.apk)
* **Chrome Extension**: Version 3.1.2 (`extension/manifest.json`)
* **GitHub Tag**: `v3.1.2`
