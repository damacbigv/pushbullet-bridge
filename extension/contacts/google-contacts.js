/**
 * Google Contacts & Identity Management Module (Manifest V3)
 * Integrates Google People API v1 (Connections & Other Contacts) via chrome.identity OAuth 2.0,
 * combined with local device telephony contact stores for robust contact resolution,
 * identity mapping, and keyboard-accessible autocomplete.
 */

export const PEOPLE_CONNECTIONS_ENDPOINT =
  'https://people.googleapis.com/v1/people/me/connections?personFields=names,phoneNumbers,photos&pageSize=300';

export const OTHER_CONTACTS_ENDPOINT =
  'https://people.googleapis.com/v1/otherContacts?readMask=names,phoneNumbers,photos&pageSize=300';

/**
 * Initiates Google OAuth2 authentication flow and retrieves user contacts from Google Address Book.
 * Automatically queries both primary connections (My Contacts) and Other Contacts.
 */
export async function syncGoogleContacts(interactive = true) {
  try {
    let token = await getAuthToken(interactive);
    if (!token) {
      throw new Error('Google authentication was cancelled or token unavailable.');
    }

    // Attempt primary connections fetch with token refresh retry
    let primaryData = null;
    let otherData = null;

    try {
      primaryData = await fetchJsonWithAuth(PEOPLE_CONNECTIONS_ENDPOINT, token);
    } catch (err) {
      if (err.status === 401) {
        // Token expired, invalidate and retry once with a fresh token
        await removeCachedAuthToken(token);
        token = await getAuthToken(true);
        if (!token) throw new Error('Failed to refresh Google authorization token.');
        primaryData = await fetchJsonWithAuth(PEOPLE_CONNECTIONS_ENDPOINT, token);
      } else {
        throw err;
      }
    }

    // Attempt otherContacts fetch (contacts messaged previously)
    try {
      otherData = await fetchJsonWithAuth(OTHER_CONTACTS_ENDPOINT, token);
    } catch (otherErr) {
      console.info('[Google Contacts] otherContacts skipped or scope not granted:', otherErr.message);
    }

    return parseAndCacheAllContacts(primaryData, otherData);
  } catch (err) {
    console.warn('[Google Contacts] Sync issue:', err.message);

    // Fall back to existing cached contacts or default sample contacts for developer mode
    const { googleContactsCache = [] } = await chrome.storage.local.get('googleContactsCache');
    return {
      success: false,
      error: err.message,
      contacts: googleContactsCache.length > 0 ? googleContactsCache : getFallbackSampleContacts()
    };
  }
}

/**
 * Authenticated JSON fetcher helper
 */
async function fetchJsonWithAuth(url, token) {
  const response = await fetch(url, {
    headers: {
      'Authorization': `Bearer ${token}`,
      'Accept': 'application/json'
    }
  });

  if (!response.ok) {
    const error = new Error(`Google People API error: ${response.status} ${response.statusText}`);
    error.status = response.status;
    throw error;
  }

  return response.json();
}

/**
 * Retrieves OAuth token via chrome.identity
 */
export function getAuthToken(interactive = true) {
  return new Promise((resolve, reject) => {
    if (!chrome.identity || !chrome.identity.getAuthToken) {
      reject(new Error('chrome.identity API is not available in this context.'));
      return;
    }

    chrome.identity.getAuthToken({ interactive }, (token) => {
      if (chrome.runtime.lastError) {
        reject(new Error(chrome.runtime.lastError.message));
      } else {
        resolve(token);
      }
    });
  });
}

/**
 * Removes cached OAuth token from chrome.identity
 */
export function removeCachedAuthToken(token) {
  return new Promise((resolve) => {
    if (!chrome.identity || !chrome.identity.removeCachedAuthToken) {
      resolve();
      return;
    }
    chrome.identity.removeCachedAuthToken({ token }, () => resolve());
  });
}

/**
 * Parses and merges connections and otherContacts into a unified, deduplicated list
 */
async function parseAndCacheAllContacts(primaryApiData, otherApiData) {
  const contacts = [];

  // 1. Parse primary connections
  if (primaryApiData?.connections) {
    for (const person of primaryApiData.connections) {
      const name = person.names?.[0]?.displayName || person.names?.[0]?.givenName;
      const phoneNumbers = person.phoneNumbers || [];
      const photoUrl = person.photos?.[0]?.url;

      if (!name && phoneNumbers.length === 0) continue;

      for (const phone of phoneNumbers) {
        const rawValue = phone.value || '';
        const cleanDigits = normalizePhoneNumber(rawValue);

        contacts.push({
          id: person.resourceName || `c_${cleanDigits}`,
          name: name || rawValue,
          phone: rawValue,
          cleanDigits,
          photoUrl: photoUrl && !photoUrl.includes('default-user') ? photoUrl : null,
          initials: getInitials(name || rawValue),
          type: phone.type || 'mobile',
          source: 'google'
        });
      }
    }
  }

  // 2. Parse otherContacts
  if (otherApiData?.otherContacts) {
    for (const person of otherApiData.otherContacts) {
      const name = person.names?.[0]?.displayName || person.names?.[0]?.givenName;
      const phoneNumbers = person.phoneNumbers || [];
      const photoUrl = person.photos?.[0]?.url;

      if (!name && phoneNumbers.length === 0) continue;

      for (const phone of phoneNumbers) {
        const rawValue = phone.value || '';
        const cleanDigits = normalizePhoneNumber(rawValue);

        contacts.push({
          id: person.resourceName || `oc_${cleanDigits}`,
          name: name || rawValue,
          phone: rawValue,
          cleanDigits,
          photoUrl: photoUrl && !photoUrl.includes('default-user') ? photoUrl : null,
          initials: getInitials(name || rawValue),
          type: phone.type || 'other',
          source: 'google'
        });
      }
    }
  }

  // Deduplicate by cleanDigits
  const uniqueContactsMap = new Map();
  for (const c of contacts) {
    if (c.cleanDigits && !uniqueContactsMap.has(c.cleanDigits)) {
      uniqueContactsMap.set(c.cleanDigits, c);
    }
  }

  const finalContacts = Array.from(uniqueContactsMap.values()).sort((a, b) =>
    a.name.localeCompare(b.name)
  );

  await chrome.storage.local.set({
    googleContactsCache: finalContacts,
    googleContactsLastSync: Date.now()
  });

  return {
    success: true,
    contacts: finalContacts
  };
}

/**
 * Normalizes a phone number into bare digits for reliable comparison.
 * Extracts last 10 digits for standard North American matching or full digits.
 */
export function normalizePhoneNumber(raw) {
  if (!raw) return '';
  const digits = String(raw).replace(/\D/g, '');
  if (digits.length === 11 && digits.startsWith('1')) {
    return digits.substring(1);
  }
  return digits;
}

/**
 * Formats a raw phone number into standard human-readable format.
 * Gracefully falls back if already formatted or non-standard digits.
 */
export function formatPhoneNumber(raw) {
  if (!raw) return '';
  const digits = String(raw).replace(/\D/g, '');

  if (digits.length === 10) {
    return `+1 (${digits.slice(0, 3)}) ${digits.slice(3, 6)}-${digits.slice(6)}`;
  }
  if (digits.length === 11 && digits.startsWith('1')) {
    const core = digits.substring(1);
    return `+1 (${core.slice(0, 3)}) ${core.slice(3, 6)}-${core.slice(6)}`;
  }
  if (digits.length > 6) {
    return `+${digits.slice(0, digits.length - 7)} ${digits.slice(-7, -4)}-${digits.slice(-4)}`;
  }
  return raw;
}

/**
 * Resolves a phone number or address to a contact record from Google Contacts or device contacts.
 * If found, returns { name, avatarUrl, initials, phone, isResolved: true }.
 * If no match exists, gracefully falls back to formatted phone number.
 */
export function resolveContactIdentity(phoneOrAddress, googleContacts = [], deviceContacts = []) {
  if (!phoneOrAddress) {
    return { name: 'Unknown', avatarUrl: null, initials: '??', phone: '', isResolved: false };
  }

  const normalized = normalizePhoneNumber(phoneOrAddress);

  // 1. Check Google Contacts first
  for (const c of googleContacts) {
    if (!c) continue;
    if (c.cleanDigits && normalized && (c.cleanDigits === normalized || c.cleanDigits.endsWith(normalized) || normalized.endsWith(c.cleanDigits))) {
      return {
        name: c.name,
        avatarUrl: c.photoUrl || null,
        initials: c.initials || getInitials(c.name),
        phone: c.phone || phoneOrAddress,
        isResolved: true,
        source: 'google'
      };
    }
  }

  // 2. Check Device Contacts next
  for (const c of deviceContacts) {
    if (!c) continue;
    const cClean = c.cleanDigits || normalizePhoneNumber(c.phone);
    if (cClean && normalized && (cClean === normalized || cClean.endsWith(normalized) || normalized.endsWith(cClean))) {
      return {
        name: c.name,
        avatarUrl: null,
        initials: getInitials(c.name),
        phone: c.phone || phoneOrAddress,
        isResolved: true,
        source: 'device'
      };
    }
  }

  // 3. Fallback: formatted readable phone number
  const formatted = formatPhoneNumber(phoneOrAddress);
  return {
    name: formatted,
    avatarUrl: null,
    initials: getInitials(formatted),
    phone: phoneOrAddress,
    isResolved: false,
    source: 'raw'
  };
}

/**
 * Searches combined contacts (Google Contacts + Device Contacts) with responsive filtering.
 */
export async function searchCombinedContacts(query) {
  const {
    googleContactsCache = [],
    deviceContactsCache = {}
  } = await chrome.storage.local.get(['googleContactsCache', 'deviceContactsCache']);

  const deviceList = Array.isArray(deviceContactsCache)
    ? deviceContactsCache
    : Object.values(deviceContactsCache);

  const googleList = googleContactsCache.length > 0
    ? googleContactsCache
    : getFallbackSampleContacts();

  const mergedMap = new Map();

  for (const c of googleList) {
    const key = normalizePhoneNumber(c.cleanDigits || c.phone);
    if (key) mergedMap.set(key, { ...c, source: 'google' });
  }

  for (const d of deviceList) {
    const key = normalizePhoneNumber(d.cleanDigits || d.phone);
    if (key && !mergedMap.has(key)) {
      mergedMap.set(key, {
        id: d.id || `dev_${key}`,
        name: d.name || d.phone,
        phone: d.phone,
        cleanDigits: key,
        photoUrl: null,
        initials: getInitials(d.name || d.phone),
        type: 'mobile',
        source: 'device'
      });
    }
  }

  const allContacts = Array.from(mergedMap.values());

  if (!query || !query.trim()) {
    return allContacts.slice(0, 10);
  }

  const q = query.trim().toLowerCase();
  const digits = q.replace(/\D/g, '');

  return allContacts.filter(c => {
    const nameMatch = c.name.toLowerCase().includes(q);
    const phoneMatch = digits.length > 0 && (c.cleanDigits.includes(digits) || c.phone.includes(digits));
    return nameMatch || phoneMatch;
  }).slice(0, 10);
}

/**
 * Backward compatibility alias for searchContacts
 */
export async function searchContacts(query) {
  return searchCombinedContacts(query);
}

export function getInitials(name) {
  if (!name) return '??';
  const clean = String(name).trim();
  if (clean.startsWith('+')) {
    const digits = clean.replace(/\D/g, '');
    return digits.slice(-2);
  }
  const parts = clean.split(/\s+/);
  if (parts.length === 1) return parts[0].substring(0, 2).toUpperCase();
  return (parts[0][0] + parts[parts.length - 1][0]).toUpperCase();
}

/**
 * Developer sample contacts used if Google Cloud Client ID is not yet provisioned.
 */
function getFallbackSampleContacts() {
  return [
    {
      id: 'demo_1',
      name: 'Alice Cooper',
      phone: '+1 (555) 234-5678',
      cleanDigits: '5552345678',
      photoUrl: null,
      initials: 'AC',
      type: 'mobile',
      source: 'google'
    },
    {
      id: 'demo_2',
      name: 'Bob Martinez',
      phone: '+1 (555) 876-5432',
      cleanDigits: '5558765432',
      photoUrl: null,
      initials: 'BM',
      type: 'mobile',
      source: 'google'
    },
    {
      id: 'demo_3',
      name: 'Sarah Connor',
      phone: '+1 (555) 901-2345',
      cleanDigits: '5559012345',
      photoUrl: null,
      initials: 'SC',
      type: 'work',
      source: 'google'
    },
    {
      id: 'demo_4',
      name: 'David Miller',
      phone: '+1 (555) 432-1098',
      cleanDigits: '5554321098',
      photoUrl: null,
      initials: 'DM',
      type: 'home',
      source: 'google'
    }
  ];
}
