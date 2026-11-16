# PushBridge v3.3.0 - Unified Thread & Message Deduplication Engine

## Overview
PushBridge **v3.3.0** is a major bug-fix update for both the Android companion app and Chrome extension, resolving conversation thread duplication and overlapping message entries in the UI. 

Prior versions suffered from thread fragmentation caused by telephony SQLite multi-threading, self-email pollution in MMS PDU headers (e.g. `volliver@gmail.com`), carrier MMS routing gateway tokens (e.g. `+444332222235`), and cross-transport overlap between Telephony SMS/MMS content providers and active RCS notification interceptors. Version 3.3.0 introduces a robust cross-platform **Message & Thread Normalization/Deduplication Engine** that merges parallel conversations into unified threads and consolidates duplicate message bubbles.

---

## What's New in Version 3.3.0

### 1. Unified Thread & Message Deduplication Engine (`ConversationDeduplicator.kt`, `extension/deduplication.js`)
* **Canonical Thread Key Generation**:
  - Deterministically maps conversation threads to canonical keys (`p2p:<digits>`, `named_group:<title>`, or `group:<sorted_digits>`).
  - **Self-Identifier Filtering**: Filters out user self-identifiers (such as `volliver@gmail.com` and cellular line numbers) so 1-on-1 conversations are never erroneously flagged as 2-person group threads.
  - **Carrier Routing Token Elimination**: Strips synthetic carrier MMS gateway prefixes and shortcode routing tokens (e.g. `+444332222235`) from group participant sets.
  - **Named Group Participant Overlap Mapping**: Maps family group threads sharing established member subsets (e.g., Mom, Dad, Wife) directly into the genuine group thread (`Parent's and Wife`).
  - **Contact Name Fallback**: Resolves corrupted contact lookup URI addresses directly to known 1-on-1 contact phone numbers.
* **Compound Message Fingerprinting**:
  - Deduplicates individual message bubbles using a compound key of `(normalized_sender, timestamp_window_±5s, message_body_hash)`.
  - Normalizes text bodies (collapsing zero-width unicode spaces `\u200B`, whitespace, and casing).
  - Automatically merges identical messages arriving across different transport layers (SMS vs. RCS), retaining richer protocol badges (`isRcs`, `isEncrypted`, `protocol`), decoded photo attachments, and delivery status.

### 2. UI Cleanup & Thread Consolidation (Chrome Extension)
* **Left-Hand Conversation List Consolidation (`popup.js`)**:
  - Automatically groups and consolidates parallel thread stubs before rendering pagination limits or search filters.
  - Fixes badge counts and thread summaries so duplicate entries never inflate the total conversation count.
* **Timeline Bubble Deduplication**:
  - Runs compound message deduplication before rendering chat bubbles in the right-hand message pane, eliminating identical duplicate bubbles.
* **Notification Injection Guard (`background.js`)**:
  - Refactors `injectNotificationIntoSmsThreadsInMemory` to match existing threads via canonical keys, preventing incoming notifications from spawning redundant `8000000000+` thread stubs.

### 3. Android Tablet & Companion App Consolidation (`MessagesTab.kt`, `PushBridgeFirebaseManager.kt`, `SmsRepository.kt`)
* **Dual-Pane Tablet Thread List (`MessagesTab.kt`)**:
  - Renders deduplicated threads in the tablet split-pane (`36% / 64%`), ensuring smooth selection and navigation.
* **Clean Database Normalization (`PushBridgeFirebaseManager.kt`)**:
  - `syncAllSmsThreads()` deduplicates all telephony and cached RCS threads before uploading, replacing old fragmented database keys with clean unified threads.
  - Live StateFlow listener (`_syncedThreads`) runs deduplication upon snapshot delivery, guaranteeing that the tablet interface remains unified in real time.
* **Clean MMS Query Filtering (`SmsRepository.kt`)**:
  - Filters out self-emails and carrier tokens during telephony content resolver participant aggregation.

---

## Verification & Testing
* **Android Unit Tests**: Passed `./gradlew testDebugUnitTest` (including new comprehensive `ConversationDeduplicatorTest` verifying Natasha 3-thread merge, family named group merge, and compound fingerprint deduplication).
* **Extension Build & Asset Verification**: `npm run build:extension` completed successfully with zero syntax errors.
* **Live Firebase RTDB Verification**: Live database threads on `devices/android_d016a935/sms_threads` were audited and consolidated from 106 fragmented entries down to clean unique threads.

---

## Downloads
* **Android App (Phone & Galaxy Tab S7)**: [`releases/PushBridge-v3.3.0.apk`](https://github.com/damacbigv/pushbullet-bridge/releases/download/v3.3.0/PushBridge-v3.3.0.apk) — *Install on both your Pixel Phone and Galaxy Tablet for complete two-way fixes.*
* **Chrome Extension Archive**: `PushBridge-Extension-v3.3.0.zip` (run `npm run build:extension` after configuring `.env` when sideloading unpacked)
* **GitHub Tag**: `v3.3.0`
