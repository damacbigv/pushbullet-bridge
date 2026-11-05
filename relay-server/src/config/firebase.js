/**
 * Firebase Admin SDK Initialization
 */
import admin from 'firebase-admin';
import dotenv from 'dotenv';

dotenv.config();

let isFirebaseInitialized = false;
let db = null;
let messaging = null;

const projectId = process.env.FIREBASE_PROJECT_ID || 'pushclonebridge';
const clientEmail = process.env.FIREBASE_CLIENT_EMAIL;
const privateKey = process.env.FIREBASE_PRIVATE_KEY
  ? process.env.FIREBASE_PRIVATE_KEY.replace(/\\n/g, '\n')
  : undefined;

try {
  if (clientEmail && privateKey) {
    admin.initializeApp({
      credential: admin.credential.cert({
        projectId,
        clientEmail,
        privateKey
      }),
      databaseURL: process.env.FIREBASE_DATABASE_URL
    });
    isFirebaseInitialized = true;
    console.log(`[Firebase Admin] Initialized with Service Account for project: ${projectId}`);
  } else if (process.env.GOOGLE_APPLICATION_CREDENTIALS) {
    admin.initializeApp({
      credential: admin.credential.applicationDefault(),
      databaseURL: process.env.FIREBASE_DATABASE_URL
    });
    isFirebaseInitialized = true;
    console.log(`[Firebase Admin] Initialized with Application Default Credentials for project: ${projectId}`);
  } else {
    // Development fallback without full admin credentials
    console.warn(`[Firebase Admin] Notice: No Service Account credentials configured in .env. Running in standalone local sync mode for project '${projectId}'.`);
  }

  if (isFirebaseInitialized) {
    db = admin.firestore();
    messaging = admin.messaging();
  }
} catch (err) {
  console.error('[Firebase Admin] Initialization warning:', err.message);
}

export { admin, db, messaging, isFirebaseInitialized, projectId };
export default admin;
