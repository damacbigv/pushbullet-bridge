# PushBridge Chrome Extension (Manifest V3)

A fully compliant Manifest V3 Google Chrome extension providing real-time notification mirroring, inline remote replies, SMS dispatching with Base64 attachments, and universal machine clipboard synchronization via Firebase.

---

## Architecture & Features

### 1. Real-Time Service Worker (`background.js`)
* **Firebase Realtime Database Streaming**: Connects natively to `https://pushclonebridge-default-rtdb.firebaseio.com/devices.json` via Server-Sent Events (`Accept: text/event-stream`), processing push deltas (`outbound_notifications`, `outbound_sms`, `clipboard`).
* **Resilient Keep-Alives**: Uses `chrome.alarms` with periodic state verification to withstand MV3 service worker suspensions.
* **Native Desktop Toasts**: Triggers OS-level desktop notifications with `chrome.notifications.create()`, featuring custom icon assets (`icons/icon-128.png`) and native **"💬 Reply"** and **"Dismiss"** action buttons.
* **Message Dispatcher**: Relays inline replies and SMS commands into Firebase Realtime Database at `devices/{deviceId}/inbound_commands`.

### 2. Interactive Popup UI (`popup/popup.html` & `popup/popup.js`)
Styled cleanly with standalone **Tailwind CSS v4** (`popup/tailwind.css`), containing:
* **Live Notification Feed**: Displays active notifications with app badges (WhatsApp, Google Messages, Slack, Telegram, Signal), timestamps, and expandable **Inline Reply** inputs.
* **Compose SMS**:
  * Recipient phone number field with country code support.
  * Message textarea with dynamic 160-character count indicator.
  * Drag-and-drop file attachment dropzone with Base64 encoding via `FileReader.readAsDataURL()` for MMS/media transfer.
  * Instant dispatch via Android phone SIM bridge.
* **Universal Clipboard Sync**:
  * Reads machine clipboard via `navigator.clipboard.readText()` to push desktop text to Android.
  * Writes to desktop clipboard via `navigator.clipboard.writeText()` from phone clipboard nodes.

---

## How to Load in Google Chrome
1. Open Google Chrome and go to `chrome://extensions/`.
2. Toggle on **Developer mode** in the upper-right corner.
3. Click **Load unpacked**.
4. Select the [`/Users/volliver/.gemini/antigravity/scratch/pushbullet-bridge/extension`](file:///Users/volliver/.gemini/antigravity/scratch/pushbullet-bridge/extension) folder.
5. The **PushBridge** icon will appear on your Chrome extension toolbar.
