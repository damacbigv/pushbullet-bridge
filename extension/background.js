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

// Re-establish stream on browser startup
chrome.runtime.onStartup.addListener(async () => {
  console.log('[PushBridge SW] Startup event triggered. Establishing Firebase stream...');
  await startFirebaseStream();
});

// Ensure stream connects whenever service worker starts or wakes up
startFirebaseStream().catch(err => {
  console.warn('[PushBridge SW] Initial stream startup background attempt:', err.message);
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
    if (data === undefined) return;

    // 1. Initial dump or root update
    if (path === '/') {
      for (const [devId, devData] of Object.entries(data)) {
        if (devData && typeof devData === 'object') {
          if (devData.presence) {
            await handleDevicePresenceUpdate(devData.presence, devId);
          }
          if (devData.contacts) {
            await handleIncomingDeviceContacts(devData.contacts, devId);
          }
          if (devData.outbound_notifications) {
            for (const notif of Object.values(devData.outbound_notifications)) {
              await handleIncomingNotification(notif, devId, false); // don't alert old backlog
            }
          }
          if (devData.sms_threads) {
            await handleIncomingSmsThreads(devData.sms_threads, `/${devId}/sms_threads`, devId);
          }
        }
      }
      return;
    }

    // 2. Device Presence status update (e.g. /android_xxx/presence, /android_xxx/presence/lastHeartbeat, /android_xxx/presence/online)
    if (path.includes('/presence')) {
      const parts = path.split('/').filter(Boolean);
      const presenceIdx = parts.indexOf('presence');
      if (presenceIdx > 0) {
        const devId = parts[presenceIdx - 1];
        const subField = parts.length > presenceIdx + 1 ? parts[presenceIdx + 1] : undefined;
        await handleDevicePresenceUpdate(data, devId, subField);
      }
      return;
    }

    // 3. Local phonebook contacts synced from Android device
    if (path.includes('/contacts')) {
      const parts = path.split('/').filter(Boolean);
      const idx = parts.indexOf('contacts');
      const devId = idx > 0 ? parts[idx - 1] : parts[0];
      await handleIncomingDeviceContacts(data, devId);
      return;
    }

    // 4. Outbound notifications from an Android device
    if (path.includes('/outbound_notifications')) {
      const parts = path.split('/').filter(Boolean);
      const idx = parts.indexOf('outbound_notifications');
      const devId = idx > 0 ? parts[idx - 1] : parts[0];
      await handleIncomingNotification(data, devId, true);
      return;
    }

    // 5. Outbound SMS from an Android device
    if (path.includes('/outbound_sms')) {
      const parts = path.split('/').filter(Boolean);
      const idx = parts.indexOf('outbound_sms');
      const devId = idx > 0 ? parts[idx - 1] : parts[0];
      await handleIncomingSms(data, devId, true);
      return;
    }

    // 6. Clipboard sync node
    if (path.includes('/clipboard')) {
      const parts = path.split('/').filter(Boolean);
      const idx = parts.indexOf('clipboard');
      const devId = idx > 0 ? parts[idx - 1] : parts[0];
      await handleIncomingClipboard(data, devId);
      return;
    }

    // 7. Threaded SMS conversations from Android device
    if (path.includes('/sms_threads')) {
      const parts = path.split('/').filter(Boolean);
      const idx = parts.indexOf('sms_threads');
      const devId = idx > 0 ? parts[idx - 1] : parts[0];
      await handleIncomingSmsThreads(data, path, devId);
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
 * Normalizes threads map whether received as an Object or sparse Array from Firebase.
 */
function normalizeThreadsMap(raw) {
  if (!raw || typeof raw !== 'object') return {};
  const map = {};
  if (Array.isArray(raw)) {
    raw.forEach((item, idx) => {
      if (item && typeof item === 'object') {
        const id = item.threadId ? String(item.threadId) : String(idx);
        map[id] = item;
      }
    });
  } else {
    for (const [key, val] of Object.entries(raw)) {
      if (val && typeof val === 'object') {
        map[key] = val;
      }
    }
  }
  return map;
}

/**
 * Handles incoming threaded SMS conversations from Android phone.
 */
async function handleIncomingSmsThreads(data, path, sourceDeviceId) {
  if (!data) return;

  const { smsThreads = {} } = await chrome.storage.local.get('smsThreads');
  const parts = path.split('/').filter(Boolean);
  const smsIdx = parts.indexOf('sms_threads');

  let updatedThreads = { ...smsThreads };

  if (smsIdx === -1 || smsIdx === parts.length - 1) {
    // Whole map of threads updated (path ends with sms_threads or root)
    const normalized = normalizeThreadsMap(data);
    updatedThreads = { ...smsThreads, ...normalized };
  } else {
    const after = parts.slice(smsIdx + 1);
    const threadId = after[0];
    const existingThread = smsThreads[threadId] || {
      threadId: Number(threadId),
      messages: {}
    };

    if (after.length === 1) {
      // Single thread object updated
      if (data && typeof data === 'object') {
        updatedThreads[threadId] = { ...existingThread, ...data };
      }
    } else if (after.length >= 2 && after[1] === 'messages') {
      const existingMsgs = existingThread.messages || {};
      if (after.length === 2) {
        // Whole messages map for thread
        updatedThreads[threadId] = {
          ...existingThread,
          messages: { ...existingMsgs, ...data }
        };
      } else if (after.length >= 3) {
        // Single message
        const msgId = after[2];
        updatedThreads[threadId] = {
          ...existingThread,
          messages: { ...existingMsgs, [msgId]: data }
        };
      }
    } else if (after.length === 2) {
      // Direct subfield of thread (e.g. snippet, lastTimestamp, unreadCount)
      updatedThreads[threadId] = {
        ...existingThread,
        [after[1]]: data
      };
    }
  }

  await chrome.storage.local.set({
    smsThreads: updatedThreads,
    activeTargetDeviceId: sourceDeviceId
  });

  chrome.runtime.sendMessage({
    type: 'SMS_THREADS_UPDATED',
    threads: updatedThreads
  }).catch(() => {});
}

/**
 * Handles device presence state updates from Firebase RTDB.
 * Automatically triggers background sync when phone is detected online.
 * Tolerates transient network handoffs and correctly parses subfield SSE events.
 * 
 * @param {any} presence The new presence data or sub-field value
 * @param {string} devId The device identifier (e.g. android_d016a935)
 * @param {string} [subField] Optional subfield if the path was /presence/{subField}
 */
async function handleDevicePresenceUpdate(presence, devId, subField) {
  if (presence === undefined || !devId) return;

  const { devicePresenceMap = {}, activeTargetDeviceId } = await chrome.storage.local.get([
    'devicePresenceMap',
    'activeTargetDeviceId'
  ]);

  const prevPresence = devicePresenceMap[devId] || {};
  const wasOnline = prevPresence.online === true;

  let mergedPresence = { ...prevPresence };

  if (subField === 'lastHeartbeat') {
    const hb = Number(presence) || Date.now();
    mergedPresence.lastHeartbeat = hb;
    // An active heartbeat timestamp directly proves the device is alive and connected
    mergedPresence.online = true;
  } else if (subField === 'online') {
    const isExplicitOnline = presence === true;
    const isFresh = mergedPresence.lastHeartbeat && (Date.now() - mergedPresence.lastHeartbeat < 180000);
    // Tolerate brief network handoffs if last heartbeat was recent (< 3 min)
    mergedPresence.online = isExplicitOnline || isFresh;
  } else if (subField === 'deviceName') {
    mergedPresence.deviceName = String(presence);
  } else if (typeof presence === 'object' && presence !== null) {
    mergedPresence = {
      ...mergedPresence,
      ...presence
    };
    const isFresh = mergedPresence.lastHeartbeat && (Date.now() - mergedPresence.lastHeartbeat < 180000);
    mergedPresence.online = (presence.online === true) || isFresh;
  } else if (typeof presence === 'boolean') {
    const isFresh = mergedPresence.lastHeartbeat && (Date.now() - mergedPresence.lastHeartbeat < 180000);
    mergedPresence.online = presence || isFresh;
  } else if (typeof presence === 'number') {
    mergedPresence.lastHeartbeat = presence;
    mergedPresence.online = true;
  }

  // Ensure default device name fallback
  if (!mergedPresence.deviceName) {
    mergedPresence.deviceName = devId.startsWith('android') ? 'Android Phone' : devId;
  }
  if (!mergedPresence.lastHeartbeat) {
    mergedPresence.lastHeartbeat = Date.now();
  }
  mergedPresence.updatedAt = Date.now();

  const isOnline = mergedPresence.online === true;

  const updatedMap = {
    ...devicePresenceMap,
    [devId]: mergedPresence
  };

  const toSet = {
    devicePresenceMap: updatedMap
  };

  if (isOnline && (!activeTargetDeviceId || !activeTargetDeviceId.startsWith('android') || activeTargetDeviceId === devId)) {
    toSet.activeTargetDeviceId = devId;
  }

  await chrome.storage.local.set(toSet);

  console.log(`[PushBridge SW] Presence update: ${devId} is ${isOnline ? 'ONLINE' : 'OFFLINE'} (heartbeat: ${mergedPresence.lastHeartbeat})`);

  // Broadcast presence change to popup UI
  chrome.runtime.sendMessage({
    type: 'DEVICE_PRESENCE_CHANGED',
    deviceId: devId,
    presence: mergedPresence
  }).catch(() => {});

  // AUTOMATIC BACKGROUND SYNC:
  // When device transitions to online or is confirmed online, pull active SMS threads
  // and local contacts automatically without user having to manually poll
  if (isOnline && !wasOnline) {
    console.log(`[PushBridge SW] Phone ${devId} came ONLINE. Triggering automatic background sync...`);
    await autoSyncDeviceData(devId);
  }
}

/**
 * Automatically syncs threads and contacts from an online device.
 */
async function autoSyncDeviceData(devId) {
  try {
    // 1. Fetch SMS threads
    const threadsUrl = `${RTDB_BASE_URL}/devices/${devId}/sms_threads.json`;
    const threadsRes = await fetch(threadsUrl);
    if (threadsRes.ok) {
      const threadsData = await threadsRes.json();
      if (threadsData && typeof threadsData === 'object') {
        await chrome.storage.local.set({ smsThreads: threadsData });
        chrome.runtime.sendMessage({
          type: 'SMS_THREADS_UPDATED',
          threads: threadsData
        }).catch(() => {});
        console.log(`[PushBridge SW] Auto-synced ${Object.keys(threadsData).length} SMS threads from ${devId}.`);
      }
    }

    // 2. Fetch device contacts
    const contactsUrl = `${RTDB_BASE_URL}/devices/${devId}/contacts.json`;
    const contactsRes = await fetch(contactsUrl);
    if (contactsRes.ok) {
      const contactsData = await contactsRes.json();
      if (contactsData && typeof contactsData === 'object') {
        await handleIncomingDeviceContacts(contactsData, devId);
      }
    }
  } catch (err) {
    console.warn(`[PushBridge SW] Error auto-syncing data for ${devId}:`, err.message);
  }
}

/**
 * Handles incoming local device contacts synced from Android phone.
 */
async function handleIncomingDeviceContacts(contacts, devId) {
  if (!contacts) return;

  const contactsMap = typeof contacts === 'object' ? contacts : {};
  const { deviceContactsCache = {} } = await chrome.storage.local.get('deviceContactsCache');
  const mergedContacts = { ...deviceContactsCache, ...contactsMap };

  await chrome.storage.local.set({
    deviceContactsCache: mergedContacts
  });

  chrome.runtime.sendMessage({
    type: 'DEVICE_CONTACTS_UPDATED',
    contacts: mergedContacts
  }).catch(() => {});

  console.log(`[PushBridge SW] Synced ${Object.keys(mergedContacts).length} device contacts from ${devId}.`);
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
          const { devicePresenceMap = {} } = await chrome.storage.local.get('devicePresenceMap');
          sendResponse({ success: true, devices: deviceIds, presenceMap: devicePresenceMap });
          break;
        }

        case 'FETCH_SMS_THREADS': {
          const { targetDeviceId, limit } = message;
          const targetUrl = `${RTDB_BASE_URL}/devices/${targetDeviceId}/sms_threads.json`;
          const res = await fetch(targetUrl);
          const data = await res.json();
          const threads = data && typeof data === 'object' ? data : {};
          await chrome.storage.local.set({ smsThreads: threads });

          let slicedThreads = threads;
          if (limit && Number(limit) > 0) {
            const sorted = Object.values(threads).sort((a, b) => (b.lastTimestamp || 0) - (a.lastTimestamp || 0));
            const capped = sorted.slice(0, Number(limit));
            slicedThreads = {};
            capped.forEach(t => {
              slicedThreads[String(t.threadId)] = t;
            });
          }
          sendResponse({ success: true, threads: slicedThreads, allThreads: threads });
          break;
        }

        case 'SEND_CHAT_MESSAGE': {
          const { targetDeviceId, threadId, recipient, messageBody, attachment, attachmentData, attachmentMime, isGroup, recipients, isRcs, isEncrypted, protocol } = message;
          const targetUrl = `${RTDB_BASE_URL}/devices/${targetDeviceId}/inbound_commands.json`;

          const rawAttachmentData = attachment?.base64 || attachment?.data || attachmentData || null;
          const rawAttachmentMime = attachment?.type || attachmentMime || null;

          // Optimistically update local thread storage so outgoing chat bubble appears instantly
          const { smsThreads = {} } = await chrome.storage.local.get('smsThreads');
          const threadKey = String(threadId || Math.abs(recipient.split('').reduce((a, b) => (((a << 5) - a) + b.charCodeAt(0)) | 0, 0)));
          const existingThread = smsThreads[threadKey] || {
            threadId: Number(threadKey),
            address: recipient,
            isGroup: !!isGroup,
            snippet: messageBody,
            lastTimestamp: Date.now(),
            messages: {}
          };

          const effectiveIsRcs = !!(isRcs || existingThread.isRcs);
          const effectiveIsEncrypted = !!(isEncrypted || existingThread.isEncrypted);
          const effectiveProtocol = protocol || existingThread.protocol || (effectiveIsEncrypted ? 'E2EE_RCS' : (effectiveIsRcs ? 'RCS' : 'SMS'));

          const commandPayload = {
            type: 'SEND_SMS',
            threadId: threadId || 0,
            recipient,
            isGroup: !!isGroup,
            recipients: recipients || [],
            isRcs: effectiveIsRcs,
            isEncrypted: effectiveIsEncrypted,
            protocol: effectiveProtocol,
            message: messageBody,
            attachment: attachment || null,
            attachmentData: rawAttachmentData,
            attachmentMime: rawAttachmentMime,
            timestamp: Date.now(),
            status: 'pending'
          };

          const localMsgId = `local_out_${Date.now()}`;
          const newMsg = {
            id: localMsgId,
            threadId: Number(threadKey),
            address: recipient,
            body: messageBody,
            attachmentData: rawAttachmentData,
            attachmentMime: rawAttachmentMime,
            timestamp: Date.now(),
            isIncoming: false,
            isRead: true,
            isRcs: effectiveIsRcs,
            isEncrypted: effectiveIsEncrypted,
            protocol: effectiveProtocol,
            pending: true
          };

          existingThread.messages = { ...existingThread.messages, [localMsgId]: newMsg };
          existingThread.snippet = messageBody || (rawAttachmentData ? '📷 [Photo]' : '');
          existingThread.lastTimestamp = Date.now();
          if (effectiveIsRcs) existingThread.isRcs = true;
          if (effectiveIsEncrypted) existingThread.isEncrypted = true;
          if (effectiveProtocol) existingThread.protocol = effectiveProtocol;

          const updatedThreads = { ...smsThreads, [threadKey]: existingThread };
          await chrome.storage.local.set({ smsThreads: updatedThreads });

          const res = await fetch(targetUrl, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(commandPayload)
          });

          const result = await res.json();
          sendResponse({ success: res.ok, result, threadKey });
          break;
        }

        case 'FETCH_LINK_PREVIEW': {
          const { url } = message;
          if (!url) {
            sendResponse({ success: false, error: 'No URL provided' });
            break;
          }

          const { linkPreviewCache = {} } = await chrome.storage.local.get('linkPreviewCache');
          if (linkPreviewCache[url]) {
            sendResponse({ success: true, preview: linkPreviewCache[url] });
            break;
          }

          let preview = null;

          // 1. Try relay server endpoint
          try {
            const controller = new AbortController();
            const timeoutId = setTimeout(() => controller.abort(), 2000);
            const relayRes = await fetch(`http://localhost:3000/api/link-preview?url=${encodeURIComponent(url)}`, {
              signal: controller.signal
            });
            clearTimeout(timeoutId);
            if (relayRes.ok) {
              const data = await relayRes.json();
              if (data && data.success && data.preview) {
                preview = data.preview;
              }
            }
          } catch (e) {
            // Relay server offline or timed out; fallback to client fetch
          }

          // 2. Direct client-side fetch fallback (enabled via host_permissions: "https://*/*")
          if (!preview) {
            try {
              const controller = new AbortController();
              const timeoutId = setTimeout(() => controller.abort(), 3000);
              const pageRes = await fetch(url, {
                headers: { 'Accept': 'text/html,application/xhtml+xml' },
                signal: controller.signal
              });
              clearTimeout(timeoutId);

              if (pageRes.ok) {
                const html = await pageRes.text();
                const urlObj = new URL(url);
                const domain = urlObj.hostname.replace(/^www\./, '');

                const getMeta = (prop) => {
                  const m = html.match(new RegExp(`<meta[^>]+(?:property|name)=["']${prop}["'][^>]+content=["']([^"']+)["']`, 'i')) ||
                            html.match(new RegExp(`<meta[^>]+content=["']([^"']+)["'][^>]+(?:property|name)=["']${prop}["']`, 'i'));
                  return m ? m[1].trim() : null;
                };

                const titleMatch = html.match(/<title[^>]*>([^<]+)<\/title>/i);
                const title = getMeta('og:title') || (titleMatch ? titleMatch[1].trim() : domain);
                const description = getMeta('og:description') || getMeta('description') || '';
                let image = getMeta('og:image') || getMeta('twitter:image') || null;
                if (image && !image.startsWith('http')) {
                  try {
                    image = new URL(image, url).href;
                  } catch {
                    image = null;
                  }
                }

                preview = {
                  url,
                  domain,
                  title: title.slice(0, 100),
                  description: description.slice(0, 160),
                  image,
                  siteName: getMeta('og:site_name') || domain
                };
              }
            } catch (err) {
              console.warn('[PushBridge SW] Client link preview fetch fallback error:', err.message);
            }
          }

          if (preview) {
            const updatedCache = { ...linkPreviewCache, [url]: preview };
            const keys = Object.keys(updatedCache);
            if (keys.length > 100) {
              delete updatedCache[keys[0]];
            }
            await chrome.storage.local.set({ linkPreviewCache: updatedCache });
            sendResponse({ success: true, preview });
          } else {
            sendResponse({ success: false, error: 'Could not fetch metadata' });
          }
          break;
        }

        case 'GET_DEVICE_PRESENCE': {
          const { targetDeviceId } = message;
          const { devicePresenceMap = {}, activeTargetDeviceId } = await chrome.storage.local.get([
            'devicePresenceMap',
            'activeTargetDeviceId'
          ]);

          const devId = targetDeviceId || activeTargetDeviceId;
          let presence = devId ? devicePresenceMap[devId] : null;

          // If no specific presence found for target devId, check if any android device exists in devicePresenceMap
          if (!presence && Object.keys(devicePresenceMap).length > 0) {
            const androidDev = Object.entries(devicePresenceMap)
              .filter(([k]) => k.startsWith('android'))
              .sort((a, b) => (b[1]?.lastHeartbeat || 0) - (a[1]?.lastHeartbeat || 0))[0];
            if (androidDev) {
              presence = androidDev[1];
            }
          }

          if (presence) {
            const isFresh = presence.lastHeartbeat && (Date.now() - presence.lastHeartbeat < 180000);
            const isOnline = presence.online === true || isFresh;
            presence = {
              ...presence,
              online: !!isOnline
            };
          }

          sendResponse({ success: true, presence, devicePresenceMap });
          break;
        }

        case 'FETCH_DEVICE_CONTACTS': {
          const { targetDeviceId } = message;
          const targetUrl = `${RTDB_BASE_URL}/devices/${targetDeviceId}/contacts.json`;
          const res = await fetch(targetUrl);
          const data = await res.json();
          const contacts = data && typeof data === 'object' ? data : {};
          await handleIncomingDeviceContacts(contacts, targetDeviceId);
          sendResponse({ success: true, contacts });
          break;
        }

        case 'TRIGGER_SMS_SYNC': {
          const { targetDeviceId, limit = 30 } = message;
          const targetUrl = `${RTDB_BASE_URL}/devices/${targetDeviceId}/inbound_commands.json`;
          const res = await fetch(targetUrl, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({
              type: 'SYNC_SMS_THREADS',
              limit: Number(limit) || 30,
              timestamp: Date.now(),
              status: 'pending'
            })
          });
          sendResponse({ success: res.ok });
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
