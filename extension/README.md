# PushBridge Chrome Extension (Manifest V3 - v2.0.0)

A fully compliant Manifest V3 Google Chrome extension providing real-time notification mirroring, threaded SMS chat, Google Contacts integration, and universal clipboard synchronization via Firebase.

---

## What's New in Version 2.0

### 1. Threaded SMS Chat Interface (Split-Pane Dashboard)
* **Classic Split-Pane Layout**:
  * **Left Sidebar**: Conversation thread list with contact avatars, last message snippets, unread badges, relative timestamps, and search.
  * **Right Pane**: Active conversation view with incoming vs. outgoing chat bubbles, timestamps, carrier status indicator, and responsive composer.
* **Full History & Thread Synchronization**: Reads and syncs full SMS message threads from the Android companion app via Firebase Realtime Database at `/devices/{deviceId}/sms_threads`.
* **Optimistic Messaging**: Typing and sending a message renders the outgoing chat bubble immediately while dispatching the command through the Android phone.

### 2. Google Contacts Integration (Google People API)
* Integrated via `chrome.identity` with OAuth 2.0 scopes:
  * `https://www.googleapis.com/auth/contacts.readonly`
  * `https://www.googleapis.com/auth/userinfo.profile`
* **Live Autocomplete**: When composing a new text or searching in the SMS tab, an autocomplete dropdown queries Google Contacts, displaying names, profile avatars/initials, and phone numbers.
* Selecting a contact automatically opens or creates the conversation thread for that recipient.

### 3. Flexible Notification Bundling & Sorting Views
* **Bundled by Source**: Groups active notifications hierarchically by application (WhatsApp, Gmail, Signal, SMS, System Alerts) with collapsible headers, unread count badges, and "Dismiss All from App".
* **Chronological Unbundled**: Flat linear timeline sorted strictly by date/time received descending.
* Dynamically updates in real time as notifications arrive or are dismissed from either the Android device or desktop.

---

## How to Load in Google Chrome
1. Open Google Chrome and go to `chrome://extensions/`.
2. Toggle on **Developer mode** in the upper-right corner.
3. Click **Load unpacked**.
4. Select the [`/Users/volliver/.gemini/antigravity/scratch/pushbullet-bridge/extension`](file:///Users/volliver/.gemini/antigravity/scratch/pushbullet-bridge/extension) folder.
