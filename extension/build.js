/**
 * Build, verification and environment injection script for PushBridge Chrome Extension (v3.0.3)
 */
import fs from 'node:fs';
import path from 'node:path';

console.log('[PushBridge Extension] Running build and asset verification...');

// 1. Environment Variable Injection for Firebase Config
const rootEnvPath = path.resolve('../.env');
const targetConfigPath = path.resolve('firebase-config.js');
const exampleConfigPath = path.resolve('firebase-config.example.js');

let envConfig = {
  apiKey: process.env.FIREBASE_API_KEY || '',
  projectId: process.env.FIREBASE_PROJECT_ID || 'pushclonebridge',
  databaseURL: process.env.FIREBASE_DATABASE_URL || 'https://pushclonebridge-default-rtdb.firebaseio.com',
  storageBucket: process.env.FIREBASE_STORAGE_BUCKET || 'pushclonebridge.firebasestorage.app',
  messagingSenderId: process.env.FIREBASE_MESSAGING_SENDER_ID || '250338142494',
  appId: process.env.FIREBASE_APP_ID || ''
};

if (fs.existsSync(rootEnvPath)) {
  const envContent = fs.readFileSync(rootEnvPath, 'utf8');
  for (const line of envContent.split('\n')) {
    const trimmed = line.trim();
    if (!trimmed || trimmed.startsWith('#') || !trimmed.includes('=')) continue;
    const [key, ...rest] = trimmed.split('=');
    const val = rest.join('=').trim().replace(/^["']|["']$/g, '');

    if (key === 'FIREBASE_API_KEY' && val) envConfig.apiKey = val;
    if (key === 'FIREBASE_PROJECT_ID' && val) envConfig.projectId = val;
    if (key === 'FIREBASE_DATABASE_URL' && val) envConfig.databaseURL = val;
    if (key === 'FIREBASE_STORAGE_BUCKET' && val) envConfig.storageBucket = val;
    if (key === 'FIREBASE_MESSAGING_SENDER_ID' && val) envConfig.messagingSenderId = val;
    if (key === 'FIREBASE_APP_ID' && val) envConfig.appId = val;
  }
}

if (envConfig.apiKey) {
  const generatedConfig = `/**
 * PushBridge Firebase Web SDK Configuration
 * Injected automatically by build.js from environment.
 * DO NOT COMMIT TO GIT.
 */
export const firebaseConfig = {
  apiKey: "${envConfig.apiKey}",
  authDomain: "${envConfig.projectId}.firebaseapp.com",
  projectId: "${envConfig.projectId}",
  databaseURL: "${envConfig.databaseURL}",
  storageBucket: "${envConfig.storageBucket}",
  messagingSenderId: "${envConfig.messagingSenderId}",
  appId: "${envConfig.appId}",
  measurementId: ""
};

export default firebaseConfig;
`;
  fs.writeFileSync(targetConfigPath, generatedConfig, 'utf8');
  console.log('✓ Successfully injected Firebase credentials from environment into firebase-config.js');
} else if (!fs.existsSync(targetConfigPath)) {
  fs.copyFileSync(exampleConfigPath, targetConfigPath);
  console.log('! No environment credentials found; instantiated firebase-config.js from template');
}

// 2. Verify Manifest V3 Assets
const manifestPath = path.resolve('manifest.json');
if (!fs.existsSync(manifestPath)) {
  console.error('Error: manifest.json missing!');
  process.exit(1);
}

const manifest = JSON.parse(fs.readFileSync(manifestPath, 'utf8'));
console.log(`✓ Manifest verified: ${manifest.name} (v${manifest.version})`);

// 3. Verify icon assets
const iconSizes = ['16', '48', '128'];
for (const size of iconSizes) {
  const iconFile = path.resolve(`icons/icon-${size}.png`);
  if (!fs.existsSync(iconFile)) {
    console.warn(`Warning: Icon icons/icon-${size}.png does not exist!`);
  } else {
    console.log(`✓ Icon verified: icons/icon-${size}.png`);
  }
}

console.log('✓ PushBridge extension build ready for unpacked loading.');
