# PushBridge Relay Server

Lightweight Node.js/Express service utilizing Firebase Admin SDK for fallback cloud sync and cross-device message dispatch between the PushBridge Chrome Extension and Android Companion App.

## Features
- **Fallback Cloud Sync**: Queues and routes notifications, SMS requests, and clipboard updates if peer-to-peer or direct Firebase Realtime channels are degraded.
- **Device Registry**: Registers active Android devices and Chrome extensions with heartbeat tracking.
- **Firebase Admin Integration**: Connects with Cloud Firestore / Firebase Cloud Messaging (FCM) using project `pushclonebridge`.

## Quick Start
```bash
# 1. Install dependencies
npm install

# 2. Configure environment (optional, defaults provided)
cp .env.example .env

# 3. Start development server
npm run dev
# Or production server
npm start
```

## API Endpoints
- `GET /health` - Health check and Firebase status
- `POST /api/sync/notification` - Ingest intercepted Android notification
- `POST /api/sync/sms` - Dispatch SMS send command to Android companion
- `POST /api/sync/clipboard` - Sync clipboard snippet across devices
- `GET /api/sync/pending` - Poll pending sync events
- `POST /api/devices/register` - Register a new paired device
- `GET /api/devices` - List paired devices
