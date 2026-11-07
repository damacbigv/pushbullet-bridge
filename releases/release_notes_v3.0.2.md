# PushBridge v3.0.2 - Multi-Member Group Thread Visibility & Security Hardening

## Overview
PushBridge Version 3.0.2 is a critical bug fix, telephony sync enhancement, and security hardening update for the cross-platform notification and SMS bridge.

---

### Key Technical Implementations

#### 1. Multi-Member Group Thread Visibility & Sync Fixes
* **Comprehensive MMS Part Address Parsing**:
  * Audited Android Telephony content provider query logic (`content://mms-sms/conversations`, `content://mms/`, and `content://mms/{mmsId}/addr`).
  * In addition to sender (`type = 137`), now parses all group recipients from MMS address headers (`type = 151` / `TO`, `type = 130` / `CC`, `type = 129` / `BCC`).
  * Automatically filters out Android telephony placeholder tokens (`insert-address-token`).
* **Multi-Recipient Participant Aggregation**:
  * Merges thread participants from Telephony canonical-address tables (`content://mms-sms/canonical-addresses`) with MMS address headers into a unified, deduplicated list of normalized E.164 phone numbers.
  * Ensures family and group threads with multiple numbers are correctly classified as `isGroup = true`, generating complete `groupTitle`s and individual participant chip metadata for the Chrome extension and tablet UI.
* **Historical Message Completeness**:
  * Upgraded default per-thread message limit from 50 to 100 historical messages.
  * Queries and sorts messages chronologically (`oldest to newest`) using `takeLast(maxMessagesPerThread)` to eliminate historical sync gaps.
  * Resolves individual sender names for incoming MMS bubbles within group chats so each participant's contributions are clearly attributed.

#### 2. Repository Security & Private GitHub Enforcement
* **Strict Secret Externalization**:
  * Externalized all configuration and credentials into `.env.example`, `android-app/app/google-services.json.example`, and `extension/firebase-config.example.js`.
  * Untracked sensitive files (`google-services.json`) from the git index while keeping local files intact for build execution.
* **Hardened `.gitignore`**:
  * Permanently ignores `.env`, `google-services.json`, `GoogleService-Info.plist`, `*serviceAccount*.json`, `*firebase-adminsdk*.json`, `node_modules/`, and build artifacts.
* **Private Repository Verification**:
  * Enforced private visibility on GitHub repository `damacbigv/pushbullet-bridge` (`"private": true, "visibility": "private"`).
  * Provided an automated setup script [`scripts/init-private-repo.sh`](file:///Users/volliver/.gemini/antigravity/scratch/pushbullet-bridge/scripts/init-private-repo.sh) to ensure new clones and forks maintain private repository status and check for staged secrets.

---

### Artifacts & Downloads
* **Android APK**: `releases/PushBridge-v3.0.2.apk` (13 MB)
* **Chrome Extension**: Version 3.0.2 (`extension/manifest.json`)
