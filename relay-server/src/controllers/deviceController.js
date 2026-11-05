/**
 * Device Controller - Manages paired client registrations (Android and Chrome Extension)
 */
import { db, isFirebaseInitialized } from '../config/firebase.js';

// In-memory registered devices
const registeredDevices = new Map();

export const registerDevice = async (req, res) => {
  try {
    const { deviceId, name, type, fcmToken } = req.body;

    if (!deviceId) {
      return res.status(400).json({ error: 'deviceId is required' });
    }

    const device = {
      deviceId,
      name: name || (type === 'android' ? 'Android Device' : 'Chrome Browser'),
      type: type || 'unknown',
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
      message: 'Device registered successfully',
      device
    });
  } catch (err) {
    return res.status(500).json({ error: err.message });
  }
};

export const listDevices = async (req, res) => {
  try {
    const devices = Array.from(registeredDevices.values());
    return res.json({
      success: true,
      count: devices.length,
      devices
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
    return res.status(404).json({ error: 'Device not found' });
  } catch (err) {
    return res.status(500).json({ error: err.message });
  }
};
