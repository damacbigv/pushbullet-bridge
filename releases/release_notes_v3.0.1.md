## PushBridge Version 3.0.1 Precision Release

### Highlights & Date/Timestamp Improvements

1. **Context-Aware Timestamp Formatting in SMS Thread Navigation**:
   - **Today's Messages**: Displays only the localized time (e.g., `3:42 PM` or `15:42`).
   - **Older Messages**: Displays the day of the week combined with the month and year (e.g., `Wed, Nov 2026`).

2. **Interactive Hover Tooltips with Full Precision**:
   - Native HTML `title` hints on timestamp elements that pop up immediately upon mouse hover.
   - Displays the complete, exact timestamp down to the second (e.g., `Wednesday, November 7, 2026 at 05:07:53 PM`).

3. **Multi-Platform Consistency**:
   - Implemented across the Chrome Extension popup, Android Tablet dashboard preview, and iOS/iPad companion stub view.
   - Preserves all thread pagination limits (30/50/100), in-memory quick text search, group chat broadcasts, and media lightbox capabilities.

### Included Assets
- **`PushBridge-v3.0.1.apk`** (13 MB): Production debug APK with telephony-optional tablet support and timestamp precision formatting.
