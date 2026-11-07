## PushBridge Version 3.0.0 Release

### Highlights & Key Improvements

1. **Scalable SMS Thread Pagination (30 / 50 / 100 Threads)**:
   - Dynamic selector allows switching thread display limits between 30, 50, and 100 conversations without UI lag or memory bloat.
   - Android telephony queries slice and order threads by `lastTimestamp DESC` at the Content Provider layer before transmission.

2. **True Group Message Thread Handling & Broadcast Replies**:
   - Multi-recipient MMS group conversations are accurately preserved and grouped by canonical conversation IDs.
   - Outbound replies from Chrome Extension or Wi-Fi tablets route through the phone's telephony pipeline as a unified group broadcast rather than fragmenting into direct messages.

3. **In-Memory Quick Text Search with Real-Time Highlighting**:
   - High-performance client-side search indexes all loaded thread names, numbers, snippets, and deep message history bodies.
   - Instant search keyword highlighting with `<mark>` badges in thread list cards and chat bubbles.
   - Interactive feedback strip and one-click clear button.

4. **Inline Photo & Media Rendering with Click-to-Expand Lightbox**:
   - MMS image parts (`image/*`) are parsed from Android telephony storage and encoded as Base64 data URIs.
   - Chat bubbles render responsive photo thumbnails with hover indicators.
   - Click opens fullscreen lightbox modal with direct download and `Escape` key dismissal.

5. **URL Link Preview Thumbnails (OpenGraph Engine)**:
   - Detects web links in message bodies and renders clickable links.
   - In-memory caching OpenGraph parser displays rich card previews with page title, site domain, description, and featured thumbnail.

6. **Wi-Fi Tablet Companion App & Multi-Device Sync Topology**:
   - Companion app installable on Wi-Fi-only tablets (e.g. Samsung Galaxy Tab S7) by declaring telephony features optional.
   - Adaptive dual-column layout for tablet displays ($\ge 600\,\text{dp}$).
   - Peer-sync topology via Firebase Realtime Database for cross-device message synchronization.
   - Modular iOS/iPad companion stub architecture included in `/ios-companion`.

### Included Assets
- **`PushBridge-v3.0.0.apk`** (16 MB): Production debug APK with telephony-optional tablet support.
