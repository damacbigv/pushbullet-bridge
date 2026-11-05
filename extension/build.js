/**
 * Build and verification script for PushBridge Chrome Extension
 */
import fs from 'node:fs';
import path from 'node:path';

console.log('[PushBridge Extension] Verifying Manifest V3 assets...');

const manifestPath = path.resolve('manifest.json');
if (!fs.existsSync(manifestPath)) {
  console.error('Error: manifest.json missing!');
  process.exit(1);
}

const manifest = JSON.parse(fs.readFileSync(manifestPath, 'utf8'));
console.log(`Manifest loaded: ${manifest.name} (v${manifest.version})`);

// Verify icon assets
const iconSizes = ['16', '48', '128'];
for (const size of iconSizes) {
  const iconFile = path.resolve(`icons/icon-${size}.png`);
  if (!fs.existsSync(iconFile)) {
    console.warn(`Warning: Icon icons/icon-${size}.png does not exist!`);
  } else {
    console.log(`✓ Icon verified: icons/icon-${size}.png`);
  }
}

console.log('PushBridge extension is ready for unpacked loading in chrome://extensions');
