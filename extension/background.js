/**
 * PushBridge Background Service Worker (Manifest V3)
 * Maintains real-time connection to Firebase Realtime Database (SSE),
 * monitors incoming notifications/SMS/clipboard, and dispatches native desktop toasts.
 */
import { firebaseConfig } from './firebase-config.js';

const ALARM_STREAM_HEARTBEAT = 'pushbridge_stream_heartbeat';
const RTDB_BASE_URL = firebaseConfig.databaseURL || 'https://pushclonebridge-default-rtdb.firebaseio.com';

let sseAbortController = null;
let isConnecting = false;

// Initialize on install or startup
chrome.runtime.onInstalled.addListener(async () => {
  const existing = await chrome.storage.local.get([
    'deviceId',
    'notificationHistory',
    'seenNotificationIds',
    'activeTargetDeviceId',
    'autoClipboardSync',
    'syncedClipboard'
  ]);

  const deviceId = existing.deviceId || 'chrome_' + Math.random().toString(36).substring(2, 10);

  await chrome.storage.local.set({
    deviceId,
    notificationHistory: existing.notificationHistory || [],
    seenNotificationIds: existing.seenNotificationIds || [],
    activeTargetDeviceId: existing.activeTargetDeviceId || '',
    autoClipboardSync: existing.autoClipboardSync ?? true,
    syncedClipboard: existing.syncedClipboard || null,
    firebaseProjectId: firebaseConfig.projectId,
    streamStatus: 'connecting'
  });

  // Setup periodic heartbeat to ensure connection survives service worker suspension
  await chrome.alarms.create(ALARM_STREAM_HEARTBEAT, { periodInMinutes: 1 });

  console.log('[PushBridge SW] Installed. Device ID:', deviceId);
  await startFirebaseStream();
});

// Periodic alarm handler for stream resilience
chrome.alarms.onAlarm.addListener(async (alarm) => {
  if (alarm.name === ALARM_STREAM_HEARTBEAT) {
    if (!sseAbortController || sseAbortController.signal.aborted) {
      console.log('[PushBridge SW] Reconnecting Firebase real-time stream via alarm...');
      await startFirebaseStream();
    }
  }
});

/**
 * Connects to Firebase Realtime Database using Server-Sent Events (SSE).
 * Works natively in Chrome Service Workers without large SDK bundles or CSP restrictions.
 */
async function startFirebaseStream() {
  if (isConnecting) return;
  isConnecting = true;

  if (sseAbortController) {
    sseAbortController.abort();
  }
  sseAbortController = new AbortController();
  const { signal } = sseAbortController;

  const streamUrl = `${RTDB_BASE_URL}/devices.json`;

  try {
    console.log('[PushBridge SW] Connecting to Firebase Realtime stream:', streamUrl);
    await chrome.storage.local.set({ streamStatus: 'connecting' });

    const response = await fetch(streamUrl, {
      headers: {
        'Accept': 'text/event-stream'
      },
      signal
    });

    if (!response.ok) {
      throw new Error(`Firebase stream HTTP error: ${response.status} ${response.statusText}`);
    }

    await chrome.storage.local.set({ streamStatus: 'connected' });
    isConnecting = false;
    console.log('[PushBridge SW] Real-time Firebase stream established.');

    const reader = response.body.getReader();
    const decoder = new TextDecoder('utf-8');
    let buffer = '';

    while (true) {
      const { done, value } = await reader.read();
      if (done) break;

      buffer += decoder.decode(value, { stream: true });
      const lines = buffer.split('\n');
      buffer = lines.pop(); // Keep partial line

      let currentEvent = 'message';
      for (const line of lines) {
        const trimmed = line.trim();
        if (!trimmed) continue;

        if (trimmed.startsWith('event:')) {
          currentEvent = trimmed.slice(6).trim();
        } else if (trimmed.startsWith('data:')) {
          const rawData = trimmed.slice(5).trim();
          await processStreamData(currentEvent, rawData);
        }
      }
    }
  } catch (err) {
    isConnecting = false;
    if (err.name !== 'AbortError') {
      console.warn('[PushBridge SW] Firebase stream connection error:', err.message);
      await chrome.storage.local.set({ streamStatus: 'error' });
    }
  }
}

/**
 * Parses and dispatches incoming Firebase Realtime nodes.
 */
async function processStreamData(eventType, rawData) {
  if (!rawData || rawData === 'null') return;

  try {
    const payload = JSON.parse(rawData);
    if (!payload || typeof payload !== 'object') return;

    const path = payload.path || '';
    const data = payload.data;
    if (!data) return;

    // 1. Initial dump or root update
    if (path === '/') {
      for (const [devId, devData] of Object.entries(data)) {
        if (devData && typeof devData === 'object') {
          if (devData.outbound_notifications) {
            for (const notif of Object.values(devData.outbound_notifications)) {
              await handleIncomingNotification(notif, devId, false); // don't alert old backlog
            }
          }
        }
      }
      return;
    }

    // 2. Outbound notifications from an Android device
    if (path.includes('/outbound_notifications')) {
      const parts = path.split('/').filter(Boolean);
      const devId = parts[0];
      await handleIncomingNotification(data, devId, true);
      return;
    }

    // 3. Outbound SMS from an Android device
    if (path.includes('/outbound_sms')) {
      const parts = path.split('/').filter(Boolean);
      const devId = parts[0];
      await handleIncomingSms(data, devId, true);
      return;
    }

    // 4. Clipboard sync node
    if (path.includes('/clipboard')) {
      const parts = path.split('/').filter(Boolean);
      const devId = parts[0];
      await handleIncomingClipboard(data, devId);
      return;
    }
  } catch (e) {
    console.error('[PushBridge SW] Error parsing stream payload:', e.message);
  }
}

/**
 * Handles an intercepted notification node from the phone.
 */
async function handleIncomingNotification(notif, sourceDeviceId, shouldTriggerAlert = true) {
  if (!notif || !notif.id) return;

  const { seenNotificationIds = [], notificationHistory = [] } = await chrome.storage.local.get([
    'seenNotificationIds',
    'notificationHistory'
  ]);

  if (seenNotificationIds.includes(notif.id)) {
    return; // Already processed
  }

  // Update history & seen IDs
  const updatedSeen = [notif.id, ...seenNotificationIds.slice(0, 200)];
  const item = {
    id: notif.id,
    notificationKey: notif.notificationKey || '',
    packageName: notif.packageName || '',
    appName: notif.appName || 'Android App',
    title: notif.title || 'Notification',
    text: notif.text || '',
    subText: notif.subText || '',
    canReply: !!notif.canReply,
    replyActionTitle: notif.replyActionTitle || 'Reply',
    isMessagingApp: !!notif.isMessagingApp,
    sourceDeviceId: sourceDeviceId || notif.sourceDeviceId || 'android',
    timestamp: notif.timestamp || Date.now()
  };

  const updatedHistory = [item, ...notificationHistory.filter(n => n.id !== notif.id).slice(0, 49)];
  await chrome.storage.local.set({
    seenNotificationIds: updatedSeen,
    notificationHistory: updatedHistory,
    activeTargetDeviceId: sourceDeviceId
  });

  // Notify active popup UI if open
  chrome.runtime.sendMessage({ type: 'NOTIFICATION_RECEIVED', notification: item }).catch(() => {});

  // Trigger Native Desktop Toast via Chrome Notifications API
  if (shouldTriggerAlert) {
    await showDesktopToast(item);
  }
}

/**
 * Handles incoming SMS node from phone.
 */
async function handleIncomingSms(sms, sourceDeviceId, shouldTriggerAlert = true) {
  if (!sms || !sms.id) return;

  const notifLike = {
    id: sms.id,
    notificationKey: '',
    packageName: 'sms',
    appName: 'SMS Text Message',
    title: sms.sender || 'Unknown Sender',
    text: sms.body || '',
    canReply: false, // SMS compose tab is used for replies
    isMessagingApp: true,
    sourceDeviceId: sourceDeviceId || 'android',
    timestamp: sms.timestamp || Date.now()
  };

  await handleIncomingNotification(notifLike, sourceDeviceId, shouldTriggerAlert);
}

/**
 * Handles clipboard update node from phone.
 */
async function handleIncomingClipboard(clip, sourceDeviceId) {
  if (!clip || !clip.text) return;
  if (clip.source === 'chrome_desktop') return; // Ignore echo

  await chrome.storage.local.set({
    syncedClipboard: {
      text: clip.text,
      sourceDeviceId: sourceDeviceId || 'android',
      timestamp: clip.timestamp || Date.now()
    }
  });

  chrome.runtime.sendMessage({
    type: 'CLIPBOARD_UPDATED',
    clipboard: clip
  }).catch(() => {});
}

/**
 * Displays a native Chrome desktop notification with action buttons.
 */
async function showDesktopToast(item) {
  const notifOptions = {
    type: 'basic',
    iconUrl: chrome.runtime.getURL('icons/icon-128.png'),
    title: `${item.appName} • ${item.title}`,
    message: item.text || item.subText || '',
    priority: 2,
    requireInteraction: false
  };

  // Add native action button for inline reply
  if (item.canReply) {
    notifOptions.buttons = [
      { title: '💬 Reply' },
      { title: 'Dismiss' }
    ];
  } else {
    notifOptions.buttons = [
      { title: 'Dismiss' }
    ];
  }

  await chrome.notifications.create(item.id, notifOptions);
}

// Notification button click handler
chrome.notifications.onButtonClicked.addListener(async (notificationId, buttonIndex) => {
  if (buttonIndex === 0) {
    // Reply clicked: record pending reply target and clear toast
    const { notificationHistory = [] } = await chrome.storage.local.get('notificationHistory');
    const target = notificationHistory.find(n => n.id === notificationId);
    if (target) {
      await chrome.storage.local.set({ pendingReplyNotification: target });
    }
    await chrome.notifications.clear(notificationId);
  } else {
    await chrome.notifications.clear(notificationId);
  }
});

// Notification body clicked
chrome.notifications.onClicked.addListener(async (notificationId) => {
  await chrome.notifications.clear(notificationId);
});

// Runtime message handler for popup requests
chrome.runtime.onMessage.addListener((message, sender, sendResponse) => {
  (async () => {
    try {
      switch (message.type) {
        case 'CHECK_STREAM': {
          if (!sseAbortController || sseAbortController.signal.aborted) {
            await startFirebaseStream();
          }
          const { streamStatus } = await chrome.storage.local.get('streamStatus');
          sendResponse({ success: true, status: streamStatus });
          break;
        }

        case 'SEND_REMOTE_REPLY': {
          const { targetDeviceId, notificationKey, replyText } = message;
          const targetUrl = `${RTDB_BASE_URL}/devices/${targetDeviceId}/inbound_commands.json`;

          const commandPayload = {
            type: 'REMOTE_REPLY',
            notificationKey,
            replyText,
            timestamp: Date.now(),
            status: 'pending'
          };

          const res = await fetch(targetUrl, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(commandPayload)
          });

          const result = await res.json();
          sendResponse({ success: res.ok, result });
          break;
        }

        case 'SEND_SMS_COMMAND': {
          const { targetDeviceId, recipient, messageBody, attachment } = message;
          const targetUrl = `${RTDB_BASE_URL}/devices/${targetDeviceId}/inbound_commands.json`;

          const commandPayload = {
            type: 'SEND_SMS',
            recipient,
            message: messageBody,
            attachment: attachment || null,
            timestamp: Date.now(),
            status: 'pending'
          };

          const res = await fetch(targetUrl, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(commandPayload)
          });

          const result = await res.json();
          sendResponse({ success: res.ok, result });
          break;
        }

        case 'SYNC_CLIPBOARD_TO_DEVICE': {
          const { targetDeviceId, text } = message;
          const targetUrl = `${RTDB_BASE_URL}/devices/${targetDeviceId}/clipboard.json`;

          const clipPayload = {
            text,
            source: 'chrome_desktop',
            timestamp: Date.now()
          };

          const res = await fetch(targetUrl, {
            method: 'PUT',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(clipPayload)
          });

          await chrome.storage.local.set({
            syncedClipboard: {
              text,
              sourceDeviceId: 'chrome_desktop',
              timestamp: Date.now()
            }
          });

          sendResponse({ success: res.ok });
          break;
        }

        case 'FETCH_DEVICES': {
          const targetUrl = `${RTDB_BASE_URL}/devices.json?shallow=true`;
          const res = await fetch(targetUrl);
          const data = await res.json();
          const deviceIds = data && typeof data === 'object' ? Object.keys(data) : [];
          sendResponse({ success: true, devices: deviceIds });
          break;
        }

        case 'TEST_NOTIFICATION_ALERT': {
          const dummy = {
            id: `test_${Date.now()}`,
            notificationKey: 'test_key',
            packageName: 'com.whatsapp',
            appName: 'WhatsApp (Test)',
            title: 'Alice Cooper',
            text: 'Hey! Mirrored notification with RemoteInput reply on your desktop.',
            canReply: true,
            replyActionTitle: 'Reply',
            isMessagingApp: true,
            sourceDeviceId: 'android_test',
            timestamp: Date.now()
          };
          await handleIncomingNotification(dummy, 'android_test', true);
          sendResponse({ success: true, notification: dummy });
          break;
        }

        default:
          sendResponse({ success: false, error: 'Unknown message type' });
      }
    } catch (err) {
      console.error('[PushBridge SW] Error handling message:', err);
      sendResponse({ success: false, error: err.message });
    }
  })();

  return true; // Keep message channel open for async response
});
