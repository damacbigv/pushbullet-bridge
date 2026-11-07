/**
 * PushBridge Relay Server v3.0
 * Lightweight Express service utilizing Firebase Admin SDK for fallback cloud sync,
 * URL OpenGraph link preview extraction, and Multi-Device peer-sync routing.
 */
import express from 'express';
import cors from 'cors';
import morgan from 'morgan';
import dotenv from 'dotenv';
import syncRoutes from './routes/sync.js';
import deviceRoutes from './routes/devices.js';
import previewRoutes from './routes/preview.js';
import { isFirebaseInitialized, projectId } from './config/firebase.js';

dotenv.config();

const app = express();
const PORT = process.env.PORT || 3000;

// Middleware
app.use(cors());
app.use(express.json({ limit: '10mb' }));
app.use(morgan('dev'));

// Routes
app.use('/api/sync', syncRoutes);
app.use('/api/devices', deviceRoutes);
app.use('/api/link-preview', previewRoutes);

// Health check endpoint
app.get('/health', (req, res) => {
  res.json({
    status: 'healthy',
    service: 'pushbridge-relay-server',
    version: '3.0.0',
    timestamp: new Date().toISOString(),
    firebase: {
      projectId,
      initialized: isFirebaseInitialized
    }
  });
});

// Root greeting
app.get('/', (req, res) => {
  res.json({
    name: 'PushBridge Relay Server',
    description: 'Cloud sync, multi-device peer pairing & link preview extraction for Chrome Extension, Android Tablet & Phone',
    version: '3.0.0',
    health: '/health',
    endpoints: {
      sync: '/api/sync',
      devices: '/api/devices',
      linkPreview: '/api/link-preview'
    }
  });
});

// Error handling middleware
app.use((err, req, res, next) => {
  console.error('[Relay Server Error]', err.stack);
  res.status(500).json({
    error: 'Internal Server Error',
    message: err.message
  });
});

app.listen(PORT, () => {
  console.log(`====================================================`);
  console.log(` PushBridge Relay Server v3.0 running on port ${PORT}`);
  console.log(` Health check: http://localhost:${PORT}/health`);
  console.log(` Firebase Project: ${projectId}`);
  console.log(` Firebase Admin Status: ${isFirebaseInitialized ? 'Connected' : 'Standalone Mode'}`);
  console.log(`====================================================`);
});

export default app;
