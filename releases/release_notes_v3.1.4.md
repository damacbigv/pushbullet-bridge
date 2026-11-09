# PushBridge v3.1.4 - RCS & Encrypted Google Messages Group Chat Discovery Fix

## Overview
PushBridge Version 3.1.4 addresses the core architectural reason why Google Messages RCS and End-to-End Encrypted (E2EE) group conversations (such as family chats like *"Dad Valla Olliver II, Mom Bertha Olliver"*) were failing to appear in the **Threaded SMS & Group Chat** tab:

### Is it an Encryption Problem?
**Yes, fundamentally.**
1. **Google Messages E2EE Sandbox**: Google Messages uses proprietary Signal-protocol End-to-End Encryption for RCS chats. Because it is encrypted end-to-end, Google Messages deliberately **does not** write these messages into Android's system telephony database (`content://mms-sms/`). Doing so would store decrypted messages in a shared SQLite table accessible to any SMS-permitted app. Instead, Google Messages stores them in its own internal private encrypted database (`bugle_db`).
2. **Retroactive Sync Barrier**: When you click "Sync Conversations" or "Fetch from Phone", Android can only query the system SMS/MMS tables. Android's security sandbox strictly forbids third-party apps from reading Google Messages' private database. Therefore, **past RCS conversation history cannot be pulled retroactively**.
3. **The Notification Gatekeeper Bug (Fixed in v3.1.4)**: Because past history cannot be queried, PushBridge must build RCS threads in real-time from intercepted notifications. However, `RcsConversationManager.kt` was previously checking for an internal AndroidX `NotificationCompat.MessagingStyle` object and dropping notifications if it was absent. Because Google Messages uses platform-level notifications without that AndroidX tag, the companion app was **silently rejecting the notification as a conversation thread** (even though it showed up as a raw notification).

---

### Key Technical Implementations

#### 1. Unlocked RCS & E2EE Group Notification Parser (`RcsConversationManager.kt`)
* **Eliminated Strict Gatekeeper**: Removed the requirement that incoming notifications must have an AndroidX `NotificationCompat.MessagingStyle` object.
* **Direct Header & Title Parsing**: If an active notification originates from Google Messages (`com.google.android.apps.messaging`) or Samsung Messages with text and title headers (e.g. `"Dad Valla Olliver II, Mom Bertha Olliver: Mom Bertha Olliver"`):
  - Automatically parses group title, sender name, and message body.
  - Automatically maps contacts and participants.
  - Instantiates or updates the RCS conversation thread (thread IDs $\ge 8{,}000{,}000{,}000$).
  - Directly syncs the thread to Firebase Realtime Database via `syncThreadDirectly`.

#### 2. Persistent Extension Thread Merging (`extension/background.js`)
* **Merge-Safe Fetching**: Updated `FETCH_SMS_THREADS` in `background.js` to merge fetched SMS threads with existing locally tracked RCS threads, preventing phone syncs from erasing active RCS discussions in Chrome.
* **Automatic Firebase Replication**: When an incoming messaging notification creates or updates a group thread in the extension, it now immediately saves the updated thread to Firebase (`PUT /sms_threads/<threadKey>.json`), guaranteeing cross-client persistence.

---

### Verification & Testing
* **Android Compilation**: `./gradlew assembleDebug` passed cleanly.
* **Extension Build**: Verified Manifest V3 build (v3.1.4) and Tailwind CSS compilation.
* **Release Artifact**: `releases/PushBridge-v3.1.4.apk`.

---

### Downloads
* **Android Companion App**: [`releases/PushBridge-v3.1.4.apk`](file:///Users/volliver/.gemini/antigravity/scratch/pushbullet-bridge/releases/PushBridge-v3.1.4.apk)
* **Chrome Extension**: Version 3.1.4
* **GitHub Tag**: `v3.1.4`
