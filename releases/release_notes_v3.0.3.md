# PushBridge v3.0.3 - Group SMS/MMS Thread Sync Deficit Fix, Public Visibility Shift & UI Theme Engine

## Overview
PushBridge Version 3.0.3 delivers critical fixes for group SMS/MMS synchronization deficits, transitions the repository to a secure public collaboration model with encrypted secret management, and introduces a vibrant dynamic UI Theme Selector across the Chrome extension and companion dashboards.

---

### Key Technical Implementations

#### 1. Group SMS/MMS Thread Sync Deficit Fixes
* **Dynamic Group Thread ID Resolution**:
  * Resolved an issue where outgoing or incoming MMS messages with missing or null thread IDs (`thread_id <= 0`) failed to group into conversations.
  * Implemented fallback thread resolution via `Telephony.Threads.getOrCreateThreadId(...)` using the aggregated participant set, ensuring multi-recipient group messages bind to their correct conversation thread.
* **Telephony Multi-URI Content Observation**:
  * Expanded `ContentObserver` registration in `PushBridgeNotificationListenerService` beyond `content://mms-sms/` to monitor `Telephony.Mms.CONTENT_URI`, `Telephony.Sms.CONTENT_URI`, `Telephony.MmsSms.CONTENT_CONVERSATIONS_URI`, and `Telephony.Threads.CONTENT_URI`.
  * Added targeted single-thread syncing (`syncSingleThread`) and secondary delayed sync (2.2s) to capture asynchronous carrier MMS image/text downloads.
* **Extension Stream Path Offsets & Recipient Normalization**:
  * Fixed stream path segment parsing in `extension/background.js` by indexing relative to `sms_threads`, preventing whole-map updates from corrupting thread IDs.
  * Added `getThreadRecipients` and `getThreadMessages` helpers in `extension/popup/popup.js` to seamlessly normalize both JSON Arrays and Firebase Realtime Database Object maps (`{ "0": {...}, "1": {...} }`), preventing runtime type errors and ensuring group chats display with participant chips and group titles.

#### 2. Repository Security Hardening & Public Visibility Shift
* **Public Repository Transition**:
  * Successfully shifted the GitHub repository `damacbigv/pushbullet-bridge` to **public** to permit direct APK download and public release artifact distribution without Android authentication barriers.
* **Complete Secret Sanitization**:
  * Audited and stripped hardcoded Firebase API keys, database URLs, and OAuth secrets from source code (`PushBridgeFirebaseManager.kt`, `extension/firebase-config.js`).
  * Injected configuration via Android `BuildConfig` generated from `.env` or system environment variables during Gradle builds.
  * Untracked `extension/firebase-config.js` from Git and created template configuration [`extension/firebase-config.example.js`](file:///Users/volliver/.gemini/antigravity/scratch/pushbullet-bridge/extension/firebase-config.example.js) and [`.env.example`](file:///Users/volliver/.gemini/antigravity/scratch/pushbullet-bridge/.env.example) with placeholder credentials.
* **CI/CD APK Workflow**:
  * Added GitHub Actions workflow [`.github/workflows/build-apk.yml`](file:///Users/volliver/.gemini/antigravity/scratch/pushbullet-bridge/.github/workflows/build-apk.yml) for automated builds on release tags using GitHub Actions Secrets.

#### 3. Vibrant UI Theme Selector & Modern Styling
* **Dynamic 5-Theme Engine**:
  * Implemented dynamic theme selector accessible via the Chrome extension header (`#theme-selector`) and Options page (`options/options.html`):
    1. **Midnight Dark** (Default): Deep obsidian slate with neon cyan/sky accents.
    2. **Solarized Clean**: Refined cream and stone aesthetic with rich blue/teal tones.
    3. **Emerald Forest**: Dark evergreen background with mint and emerald highlights.
    4. **Sunset Neon**: Deep violet-plum palette with energetic rose and magenta accents.
    5. **Classic Minimalist**: Crisp high-contrast grayscale with graphite message bubbles.
* **WCAG AA / AAA Compliance**:
  * All 5 theme palettes strictly maintain contrast ratios $\ge 4.5:1$ for body text and $\ge 3:1$ for large text and badges across chat bubbles, contact names, and timestamps.
* **Synchronized State**:
  * Theme preferences are persisted in `chrome.storage.local` and broadcast live across all extension contexts via runtime messaging.
  * Android companion app updated with a matching theme showcase card and `v3.0.3` badge.

---

### Artifacts & Downloads
* **Android APK**: [`releases/PushBridge-v3.0.3.apk`](file:///Users/volliver/.gemini/antigravity/scratch/pushbullet-bridge/releases/PushBridge-v3.0.3.apk) (13.1 MB)
* **Chrome Extension**: Version 3.0.3 (`extension/manifest.json`)
