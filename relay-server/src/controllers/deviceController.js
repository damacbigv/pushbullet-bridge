/**
 * Device Controller - Manages paired client registrations (Android Phone, Wi-Fi Tablet, Chrome Extension, and iOS Tablet)
 * Implements Multi-Device Peer-Sync Topology
 */
import { db, isFirebaseInitialized } from '../config/firebase.js';

// In-memory registered devices
const registeredDevices = new Map();

// In-memory peer command queue for offline store-and-forward between Phone and Tablet
const peerCommandQueue = new Map();

export const registerDevice = async (req, res) => {
  try {
    const { deviceId, name, type, role, fcmToken, model, manufacturer } = req.body;

    if (!deviceId) {
      return res.status(400).json({ error: 'deviceId is required' });
    }

    const deviceType = type || (deviceId.startsWith('tablet') ? 'tablet' : deviceId.startsWith('android') ? 'phone' : 'desktop');
    const deviceRole = role || (deviceType === 'phone' ? 'primary_telephony' : 'companion_client');

    const device = {
      deviceId,
      name: name || (deviceType === 'tablet' ? 'Android Wi-Fi Tablet' : deviceType === 'phone' ? 'Android Phone' : 'Chrome Extension'),
      type: deviceType,
      role: deviceRole,
      model: model || '',
      manufacturer: manufacturer || '',
      fcmToken: fcmToken || null,
      lastSeen: Date.now(),
      registeredAt: registeredDevices.get(deviceId)?.registeredAt || Date.now()
    };

    registeredDevices.set(deviceId, device);

    if (isFirebaseInitialized && db) {
      await db.collection('devices').doc(deviceId).set(device);
    }

    return res.status(200).json({
      success: true,
      message: 'Device registered in peer topology',
      device
    });
  } catch (err) {
    return res.status(500).json({ error: err.message });
  }
};

export const listDevices = async (req, res) => {
  try {
    const devices = Array.from(registeredDevices.values()).map(dev => {
      const isOnline = Date.now() - dev.lastSeen < 180000;
      return {
        ...dev,
        online: isOnline
      };
    });

    return res.json({
      success: true,
      count: devices.length,
      devices,
      topology: {
        primaryPhone: devices.find(d => d.type === 'phone' || d.role === 'primary_telephony') || null,
        companions: devices.filter(d => d.type === 'tablet' || d.type === 'desktop' || d.type === 'ios_tablet')
      }
    });
  } catch (err) {
    return res.status(500).json({ error: err.message });
  }
};

export const heartbeat = async (req, res) => {
  try {
    const { deviceId } = req.params;
    if (registeredDevices.has(deviceId)) {
      const dev = registeredDevices.get(deviceId);
      dev.lastSeen = Date.now();
      return res.json({ success: true, lastSeen: dev.lastSeen });
    }
    // Auto-register on heartbeat if new
    const newDev = {
      deviceId,
      name: deviceId,
      type: deviceId.startsWith('tablet') ? 'tablet' : deviceId.startsWith('android') ? 'phone' : 'desktop',
      lastSeen: Date.now(),
      registeredAt: Date.now()
    };
    registeredDevices.set(deviceId, newDev);
    return res.json({ success: true, lastSeen: newDev.lastSeen, created: true });
  } catch (err) {
    return res.status(500).json({ error: err.message });
  }
};

/**
 * Peer command relay: allows Tablet Companion or Chrome Extension to queue commands
 * for the Primary Phone (SMS dispatch, thread query) or vice-versa.
 */
export const dispatchPeerCommand = async (req, res) => {
  try {
    const { targetDeviceId, command, payload, sourceDeviceId } = req.body;

    if (!targetDeviceId || !command) {
      return res.status(400).json({ error: 'targetDeviceId and command are required.' });
    }

    const commandItem = {
      id: `cmd_${Date.now()}_${Math.random().toString(36).substr(2, 6)}`,
      targetDeviceId,
      sourceDeviceId: sourceDeviceId || 'unknown',
      command,
      payload: payload || {},
      status: 'pending',
      timestamp: Date.now()
    };

    if (!peerCommandQueue.has(targetDeviceId)) {
      peerCommandQueue.set(targetDeviceId, []);
    }
    peerCommandQueue.get(targetDeviceId).push(commandItem);

    return res.status(201).json({
      success: true,
      message: 'Command queued for peer device',
      command: commandItem
    });
  } catch (err) {
    return res.status(500).json({ error: err.message });
  }
};

/**
 * Polls pending peer commands for a device
 */
export const getPendingPeerCommands = async (req, res) => {
  try {
    const { deviceId } = req.params;
    const queue = peerCommandQueue.get(deviceId) || [];
    const pending = [...queue];
    peerCommandQueue.set(deviceId, []); // drain queue

    return res.json({
      success: true,
      count: pending.length,
      commands: pending
    });
  } catch (err) {
    return res.status(500).json({ error: err.message });
  }
};
