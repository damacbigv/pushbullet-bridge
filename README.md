# PushBridge

> **Self-Hosted Cross-Platform Notification Mirroring & SMS Bridge**  
> A private, modular alternative to Pushbullet connecting Google Chrome, Android devices, and a fallback cloud sync relay using Firebase as the real-time synchronization backend.

---

## Workspace Structure

```
pushbullet-bridge/
├── extension/          # Google Chrome Extension (Manifest V3)
│   ├── manifest.json   # Extension manifest & permissions
│   ├── background.js   # Background service worker with Chrome Notifications & Alarms
│   ├── firebase-config.js # Preconfigured Firebase Web SDK integration
│   ├── popup/          # Notification feed, SMS dispatcher, & push interface
│   ├── options/        # Relay server & sync configuration
│   └── icons/          # Multi-resolution icon set (16px, 48px, 128px)
│
├── android-app/        # Native Kotlin Android Companion App
│   ├── app/src/main/
│   │   ├── AndroidManifest.xml # Interception permissions & background services
│   │   └── java/com/example/pushbridge/
│   │       ├── MainActivity.kt        # Edge-to-edge Compose container & permission flows
│   │       ├── ui/main/MainScreen.kt  # Bridge status dashboard & access toggles
│   │       ├── service/               # NotificationListenerService (intercepts system notices)
│   │       ├── receiver/              # SmsReceiver (intercepts incoming text messages)
│   │       ├── util/                  # SmsManagerHelper (sends outbound SMS)
│   │       └── data/                  # PushBridgeSyncManager (dispatches payloads)
│   └── build.gradle.kts
│
├── relay-server/       # Lightweight Node.js/Express Service
│   ├── src/
│   │   ├── server.js               # Express application entry & middleware
│   │   ├── config/firebase.js      # Firebase Admin SDK initialization & fallback logic
│   │   ├── routes/                 # Sync & device management REST endpoints
│   │   └── controllers/            # Ingestion, pending queues, & device registry
│   ├── package.json
│   └── .env.example
│
├── package.json        # Workspace orchestrator scripts
└── README.md
```

---

## Firebase Configuration

Preconfigured for project **`pushclonebridge`**:

```javascript
const firebaseConfig = {
  apiKey: "AIzaSyCmoxdDpdYz_Gxsx04zXSemus6fJmA4-gU",
  authDomain: "pushclonebridge.firebaseapp.com",
  projectId: "pushclonebridge",
  storageBucket: "pushclonebridge.firebasestorage.app",
  messagingSenderId: "250338142494",
  appId: "1:250338142494:web:5029dbee8c1e9da606810f",
  measurementId: "G-S9HP35BV3G"
};
```

---

## Quick Start Guide

### 1. Relay Server (Fallback Cloud Sync)
```bash
cd relay-server
npm install
npm run dev
```
Health endpoint: `http://localhost:3000/health`

### 2. Chrome Extension (Manifest V3)
1. In Chrome, navigate to `chrome://extensions/`
2. Toggle on **Developer mode** (top right).
3. Click **Load unpacked** and select the `extension/` folder in this workspace.
4. Pin the **PushBridge** icon to your toolbar.

### 3. Android Companion App
```bash
cd android-app
./gradlew assembleDebug
```
1. Install the resulting APK on your physical Android device or emulator (`android run` or `adb install`).
2. Open the app and grant:
   - **Notification Listener Access** (allows capturing incoming Android alerts)
   - **SMS Permissions** (allows reading and replying to SMS)
3. Tap **Send Test Sync Notification** to verify end-to-end sync to Chrome.

---

## Active Workspace Recommendation

To pair program seamlessly across these three subprojects, please set this directory as your active workspace:
```
/Users/volliver/.gemini/antigravity/scratch/pushbullet-bridge
```
