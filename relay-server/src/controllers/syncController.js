/**
 * Sync Controller - Handles notification mirroring, SMS bridge, and clipboard sync
 */
import { db, messaging, isFirebaseInitialized } from '../config/firebase.js';

// In-memory fallback queue for sync events
const syncEventQueue = [];

export const postNotification = async (req, res) => {
  try {
    const { sourceDeviceId, appName, title, text, subText, packageName, timestamp } = req.body;

    if (!title && !text) {
      return res.status(400).json({ error: 'Title or text required.' });
    }

    const event = {
      id: `notif_${Date.now()}_${Math.random().toString(36).substr(2, 6)}`,
      type: 'notification',
      sourceDeviceId: sourceDeviceId || 'unknown',
      appName: appName || 'Android App',
      packageName: packageName || '',
      title: title || '',
      text: text || '',
      subText: subText || '',
      timestamp: timestamp || Date.now()
    };

    syncEventQueue.push(event);

    // Prune events older than 1 hour or keep latest 200
    if (syncEventQueue.length > 200) {
      syncEventQueue.shift();
    }

    // Persist to Cloud Firestore if connected
    if (isFirebaseInitialized && db) {
      await db.collection('notifications').doc(event.id).set(event);
    }

    return res.status(201).json({
      success: true,
      message: 'Notification mirrored successfully',
      event
    });
  } catch (err) {
    console.error('Error posting notification:', err);
    return res.status(500).json({ error: err.message });
  }
};

export const postSms = async (req, res) => {
  try {
    const { sourceDeviceId, targetDeviceId, recipient, message } = req.body;

    if (!recipient || !message) {
      return res.status(400).json({ error: 'Recipient and message required.' });
    }

    const event = {
      id: `sms_${Date.now()}_${Math.random().toString(36).substr(2, 6)}`,
      type: 'sms',
      action: 'SEND_SMS',
      sourceDeviceId: sourceDeviceId || 'chrome',
      targetDeviceId: targetDeviceId || 'android',
      recipient,
      message,
      timestamp: Date.now()
    };

    syncEventQueue.push(event);

    if (isFirebaseInitialized && db) {
      await db.collection('sms_outbox').doc(event.id).set(event);
    }

    return res.status(201).json({
      success: true,
      message: 'SMS command queued for Android dispatch',
      event
    });
  } catch (err) {
    console.error('Error dispatching SMS:', err);
    return res.status(500).json({ error: err.message });
  }
};

export const postClipboard = async (req, res) => {
  try {
    const { sourceDeviceId, text, timestamp } = req.body;

    if (!text) {
      return res.status(400).json({ error: 'Text content required for clipboard sync.' });
    }

    const event = {
      id: `clip_${Date.now()}`,
      type: 'clipboard',
      sourceDeviceId: sourceDeviceId || 'unknown',
      text,
      timestamp: timestamp || Date.now()
    };

    syncEventQueue.push(event);

    if (isFirebaseInitialized && db) {
      await db.collection('clipboard').doc('latest').set(event);
    }

    return res.status(200).json({
      success: true,
      message: 'Clipboard synced',
      event
    });
  } catch (err) {
    console.error('Error syncing clipboard:', err);
    return res.status(500).json({ error: err.message });
  }
};

export const getPendingEvents = async (req, res) => {
  try {
    const { targetDeviceId, since } = req.query;
    const sinceTimestamp = parseInt(since, 10) || 0;

    const filtered = syncEventQueue.filter(event => {
      if (event.timestamp <= sinceTimestamp) return false;
      if (event.targetDeviceId && targetDeviceId && event.targetDeviceId !== targetDeviceId) {
        return false;
      }
      return true;
    });

    return res.json({
      success: true,
      count: filtered.length,
      events: filtered
    });
  } catch (err) {
    return res.status(500).json({ error: err.message });
  }
};
