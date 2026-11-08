# PushBridge v3.1.1 - Group Message RCS/SMS Thread Resolution & Live Sync Engine Fix

## Overview
PushBridge Version 3.1.1 resolves the critical issue where incoming multi-member group messages (such as family chats from Google Messages) appeared in desktop/browser notifications but failed to populate or update the corresponding conversation thread in the **Threaded SMS & Group Chat** dashboard.

---

### Root Causes Diagnosed
1. **Google Messages Notification Title Formatting**:
   * Google Messages formats multi-member group chat notifications as `"<Group Name or Participants>: <Sender Name>"` (e.g., `"Dad Valla Olliver II, Mom Bertha Olliver: Mom Bertha Olliver"`).
   * The notification listener previously used the raw combined string containing the trailing `: <Sender Name>` suffix, preventing it from matching the phone's existing group thread (`"Dad Valla Olliver II, Mom Bertha Olliver"`).
2. **Missing Participant Phone Number Resolution**:
   * In Google Messages notifications, `Person.getUri()` is frequently null. Because phone numbers were not extracted from the participant names, `participantsSet` was empty, preventing Telephony thread matching.
3. **Database Re-Query Thread ID Mismatch in `syncSingleThread`**:
   * When `PushBridgeNotificationListenerService` called `syncSingleThread(context, threadId)`, `syncSingleThread` re-queried `SmsRepository.queryConversationThreads(context, maxThreads = 30)`.
   * When the thread merged with Telephony, its ID switched to the Telephony SQLite ID (e.g. `12`), while `syncSingleThread` checked for the synthetic RCS ID (`8000000000L + ...`). `target` evaluated to `null`, so Firebase `sms_threads` was never updated.
4. **Extension Local Storage Decoupling**:
   * The Chrome extension previously only used `outbound_sms` and `outbound_notifications` to populate the Notifications feed, without inserting the incoming message or conversation thread into `smsThreads` in `chrome.storage.local`.

---

### Key Technical Fixes Implemented

#### 1. Smart Group Title & Participant Parsing (`RcsConversationManager.kt`)
* Parses Google Messages group notifications by splitting on `:` to cleanly isolate the group title/participants (`"Dad Valla Olliver II, Mom Bertha Olliver"`) from the sender (`"Mom Bertha Olliver"`).
* Looks up each participant name in the Android address book (`ContactsContract.CommonDataKinds.Phone`) via new helper `SmsRepository.resolvePhoneNumberForContactName(...)`.
* Queries `Telephony.Threads.getOrCreateThreadId(context, participantsSet)` using the resolved phone numbers to bind the RCS session directly to the actual Telephony conversation thread ID.

#### 2. Direct Thread Synchronization (`PushBridgeFirebaseManager.kt`)
* Introduced `syncThreadDirectly(context, thread)` to write the full thread and its message items directly to `devices/$deviceId/sms_threads/${thread.threadId}` in Firebase Realtime Database without relying on slow database re-queries.
* Updated `PushBridgeNotificationListenerService.onNotificationPosted` to call `syncThreadDirectly` and trigger an automated background sync of all threads with an expanded limit of 100 conversations.

#### 3. Optimistic SMS Thread Insertion in Chrome Extension (`extension/background.js`)
* In `handleIncomingSms`, whenever an incoming SMS or RCS message arrives via `outbound_sms`, it immediately matches or creates the conversation thread in `smsThreads` inside `chrome.storage.local`.
* Sets `lastTimestamp = Date.now()`, updates the snippet, increments unread count, and broadcasts `SMS_THREADS_UPDATED` so the active group chat immediately jumps to the top of the left-hand thread list.
* Triggers an automated background `SYNC_SMS_THREADS` command whenever a notification arrives from a messaging app.

---

### Artifacts & Downloads
* **Android Companion App**: [`releases/PushBridge-v3.1.1.apk`](file:///Users/volliver/.gemini/antigravity/scratch/pushbullet-bridge/releases/PushBridge-v3.1.1.apk) (13.2 MB)
* **Chrome Extension**: Version 3.1.1 (`extension/manifest.json`)
* **GitHub Release**: [`v3.1.1`](https://github.com/damacbigv/pushbullet-bridge/releases/tag/v3.1.1)
