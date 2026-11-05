/**
 * Google Contacts Integration using Google People API v1 and chrome.identity (OAuth 2.0)
 */

export const GOOGLE_PEOPLE_API_ENDPOINT = 'https://people.googleapis.com/v1/people/me/connections?personFields=names,phoneNumbers,photos&pageSize=300';

/**
 * Initiates Google OAuth2 authentication flow and retrieves user contacts from Google Address Book.
 */
export async function syncGoogleContacts(interactive = true) {
  try {
    const token = await getAuthToken(interactive);
    if (!token) {
      throw new Error('Google authentication was cancelled or token unavailable.');
    }

    const response = await fetch(GOOGLE_PEOPLE_API_ENDPOINT, {
      headers: {
        'Authorization': `Bearer ${token}`,
        'Accept': 'application/json'
      }
    });

    if (response.status === 401) {
      // Token might be expired, remove cached token and retry once
      await removeCachedAuthToken(token);
      const freshToken = await getAuthToken(true);
      if (!freshToken) throw new Error('Failed to refresh Google authorization token.');

      const retryRes = await fetch(GOOGLE_PEOPLE_API_ENDPOINT, {
        headers: {
          'Authorization': `Bearer ${freshToken}`,
          'Accept': 'application/json'
        }
      });
      if (!retryRes.ok) throw new Error(`Google People API error: ${retryRes.statusText}`);
      const data = await retryRes.json();
      return parseAndCacheContacts(data);
    }

    if (!response.ok) {
      throw new Error(`Google People API error: ${response.status} ${response.statusText}`);
    }

    const data = await response.json();
    return parseAndCacheContacts(data);
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
 * Retrieves OAuth token via chrome.identity
 */
function getAuthToken(interactive = true) {
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

function removeCachedAuthToken(token) {
  return new Promise((resolve) => {
    if (!chrome.identity || !chrome.identity.removeCachedAuthToken) {
      resolve();
      return;
    }
    chrome.identity.removeCachedAuthToken({ token }, () => resolve());
  });
}

/**
 * Normalizes People API connections into clean contact records
 */
async function parseAndCacheContacts(apiData) {
  const connections = apiData.connections || [];
  const contacts = [];

  for (const person of connections) {
    const name = person.names?.[0]?.displayName || person.names?.[0]?.givenName;
    const phoneNumbers = person.phoneNumbers || [];
    const photoUrl = person.photos?.[0]?.url;

    if (!name && phoneNumbers.length === 0) continue;

    for (const phone of phoneNumbers) {
      const rawValue = phone.value || '';
      const cleanDigits = rawValue.replace(/\D/g, '');

      contacts.push({
        id: person.resourceName || `c_${cleanDigits}`,
        name: name || rawValue,
        phone: rawValue,
        cleanDigits,
        photoUrl: photoUrl && !photoUrl.includes('default-user') ? photoUrl : null,
        initials: getInitials(name || rawValue),
        type: phone.type || 'mobile'
      });
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
 * Searches contacts by name or phone query
 */
export async function searchContacts(query) {
  const { googleContactsCache = [] } = await chrome.storage.local.get('googleContactsCache');
  const contacts = googleContactsCache.length > 0 ? googleContactsCache : getFallbackSampleContacts();

  if (!query || !query.trim()) {
    return contacts.slice(0, 10);
  }

  const q = query.trim().toLowerCase();
  const digits = q.replace(/\D/g, '');

  return contacts.filter(c => {
    const nameMatch = c.name.toLowerCase().includes(q);
    const phoneMatch = digits.length > 0 && c.cleanDigits.includes(digits);
    return nameMatch || phoneMatch;
  }).slice(0, 8);
}

function getInitials(name) {
  if (!name) return '??';
  const parts = name.trim().split(/\s+/);
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
      cleanDigits: '15552345678',
      photoUrl: null,
      initials: 'AC',
      type: 'mobile'
    },
    {
      id: 'demo_2',
      name: 'Bob Martinez',
      phone: '+1 (555) 876-5432',
      cleanDigits: '15558765432',
      photoUrl: null,
      initials: 'BM',
      type: 'mobile'
    },
    {
      id: 'demo_3',
      name: 'Sarah Connor',
      phone: '+1 (555) 901-2345',
      cleanDigits: '15559012345',
      photoUrl: null,
      initials: 'SC',
      type: 'work'
    },
    {
      id: 'demo_4',
      name: 'David Miller',
      phone: '+1 (555) 432-1098',
      cleanDigits: '15554321098',
      photoUrl: null,
      initials: 'DM',
      type: 'home'
    }
  ];
}
