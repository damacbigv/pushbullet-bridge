/**
 * Unified Thread & Message Deduplication Engine (SMS + RCS Overlap)
 * Normalizes contact identifiers, filters carrier/self routing artifacts,
 * consolidates fragmented threads, and deduplicates message bubbles based on compound key:
 * (normalized_sender, timestamp_window_±5s, message_body_hash).
 */

const ZERO_WIDTH_REGEX = /[\u200B\u200C\u200D\u200E\u200F\uFEFF]/g;
const WHITESPACE_REGEX = /\s+/g;
const MESSAGE_COUNT_SUFFIX = /\s*\(\d+\s+messages?\)\s*$/i;

/**
 * Extracts the last 10 digits of a phone number for canonical comparison.
 */
export function normalizeDigits(phone) {
  if (!phone) return '';
  const digits = String(phone).replace(/\D/g, '');
  return digits.length > 10 ? digits.slice(-10) : digits;
}

/**
 * Checks if an address/token is the local user's self-identifier (email/phone)
 * or a carrier MMS gateway routing artifact (e.g. +444332222235).
 */
export function isSelfOrCarrierToken(raw, selfPhoneDigits = null) {
  if (!raw) return true;
  const str = String(raw).trim().toLowerCase();

  // Email addresses (e.g. volliver@gmail.com attached to MMS PDU headers)
  if (str.includes('@')) return true;

  // Placeholder tokens
  if (str.includes('insert-address-token') || str.startsWith('mailto:')) return true;

  const digits = str.replace(/\D/g, '');

  // Carrier MMS gateway or routing synthetic numbers (e.g. +44433..., 444332222235, or > 15 digits)
  if (digits.startsWith('44433') || digits.length > 15) return true;

  // User's own registered phone number
  if (selfPhoneDigits) {
    const selfNorm = normalizeDigits(selfPhoneDigits);
    if (selfNorm && digits.endsWith(selfNorm)) return true;
  }

  // Common user number if known (Valla Olliver)
  if (digits.endsWith('7578717566')) return true;

  return false;
}

/**
 * Cleans a conversation or group title by stripping lock symbols and "(XX messages)" counters.
 */
export function cleanConversationTitle(raw) {
  if (!raw) return '';
  return String(raw)
    .replace(/🔒/g, '')
    .replace(MESSAGE_COUNT_SUFFIX, '')
    .trim();
}

/**
 * Extracts non-self, non-carrier participant phone digits (10 digits) from a thread.
 */
export function extractNonSelfParticipants(thread, selfPhoneDigits = null) {
  const parts = new Set();
  const recips = thread.recipients || [];

  for (const r of recips) {
    const p = (typeof r === 'object' && r) ? (r.phone || r.cleanDigits || '') : String(r || '');
    if (!isSelfOrCarrierToken(p, selfPhoneDigits)) {
      const d = normalizeDigits(p);
      if (d.length >= 7) parts.add(d);
    }
  }

  const addr = String(thread.address || '');
  for (const item of addr.split(',')) {
    if (!isSelfOrCarrierToken(item, selfPhoneDigits)) {
      const d = normalizeDigits(item);
      if (d.length >= 7) parts.add(d);
    }
  }

  return Array.from(parts);
}

/**
 * Discovers genuine user-defined group titles and maps them to their known participants.
 */
export function buildNamedGroupMap(threadsList, selfPhoneDigits = null) {
  const map = {};

  for (const t of threadsList) {
    const title = cleanConversationTitle(t.groupTitle || '');
    if (!title) continue;

    // Genuine custom group title check (not a comma-separated list or plain number)
    const isCustom = !title.includes(',') &&
      !title.includes('&') &&
      !title.includes('@') &&
      !title.startsWith('+') &&
      !title.split('').every(c => (c >= '0' && c <= '9') || ' -().'.includes(c));

    if (isCustom) {
      const key = title.toLowerCase();
      const parts = extractNonSelfParticipants(t, selfPhoneDigits);
      if (parts.length > 0) {
        map[key] = map[key] || new Set();
        parts.forEach(p => map[key].add(p));
      }
    }
  }

  return map;
}

/**
 * Builds a map of 1-on-1 contact display names to their verified 10-digit phone number.
 */
export function buildContactNameToPhoneMap(threadsList, selfPhoneDigits = null) {
  const map = {};
  for (const t of threadsList) {
    if (!t.isGroup && t.contactName && !t.contactName.includes(',')) {
      const parts = extractNonSelfParticipants(t, selfPhoneDigits);
      if (parts.length === 1) {
        const key = t.contactName.toLowerCase().trim();
        if (!map[key]) {
          map[key] = parts[0];
        }
      }
    }
  }
  return map;
}

/**
 * Generates a deterministic canonical thread key for deduplication and grouping.
 */
export function getCanonicalThreadKey(thread, namedGroupMap = {}, contactNameToPhone = {}, selfPhoneDigits = null) {
  const parts = extractNonSelfParticipants(thread, selfPhoneDigits);
  const title = cleanConversationTitle(thread.groupTitle || '');

  const isCustom = title &&
    !title.includes(',') &&
    !title.includes('&') &&
    !title.includes('@') &&
    !title.startsWith('+') &&
    !title.split('').every(c => (c >= '0' && c <= '9') || ' -().'.includes(c));

  if (isCustom) {
    return 'named_group:' + title.toLowerCase();
  }

  // Check if participant set matches an established named group (e.g. Mom, Dad, Natasha -> Parent's and Wife)
  if (parts.length >= 2 && Object.keys(namedGroupMap).length > 0) {
    for (const [groupName, groupPartsSet] of Object.entries(namedGroupMap)) {
      let overlap = 0;
      parts.forEach(p => { if (groupPartsSet.has(p)) overlap++; });
      if (overlap >= 2 && (overlap === groupPartsSet.size || overlap === parts.length)) {
        return 'named_group:' + groupName;
      }
    }
  }

  // Standard multi-member group
  if (parts.length > 1) {
    parts.sort();
    return 'group:' + parts.join('_');
  }

  // Single participant 1-on-1 thread
  if (parts.length === 1) {
    return 'p2p:' + parts[0];
  }

  // 1-on-1 Contact Name lookup fallback if address was corrupted or missing
  const cName = thread.contactName ? String(thread.contactName).toLowerCase().trim() : '';
  if (cName && !cName.includes(',') && !thread.isGroup) {
    const mappedPhone = contactNameToPhone[cName];
    if (mappedPhone) {
      return 'p2p:' + mappedPhone;
    }
  }

  // Fallback: non-number single address or thread ID
  const cleanAddr = String(thread.address || '').trim();
  if (cleanAddr && !cleanAddr.includes(',')) {
    const d = normalizeDigits(cleanAddr);
    if (d.length >= 7) return 'p2p:' + d;
  }

  return 'tid:' + thread.threadId;
}

/**
 * Normalizes message body text for fuzzy deduplication:
 * collapses zero-width spaces, trim, and whitespace normalization.
 */
export function normalizeMessageBody(b) {
  if (!b) return '';
  return String(b)
    .replace(ZERO_WIDTH_REGEX, '')
    .replace(WHITESPACE_REGEX, ' ')
    .trim()
    .toLowerCase();
}

/**
 * Compound fingerprint check to determine if two messages represent the same event:
 * (normalized_sender, timestamp_window_±5s, message_body_hash/content).
 */
export function isDuplicateMessage(msg1, msg2, windowMs = 5000) {
  const ts1 = Number(msg1.timestamp || 0);
  const ts2 = Number(msg2.timestamp || 0);
  if (Math.abs(ts1 - ts2) > windowMs) return false;
  if (Boolean(msg1.isIncoming) !== Boolean(msg2.isIncoming)) return false;

  const getSender = (m) => {
    if (!m.isIncoming || m.address === 'Me') return 'me';
    return normalizeDigits(m.address) || String(m.contactName || '').trim().toLowerCase();
  };

  const s1 = getSender(msg1);
  const s2 = getSender(msg2);
  if (s1 && s2 && s1 !== s2) return false;

  const b1 = normalizeMessageBody(msg1.body || msg1.text);
  const b2 = normalizeMessageBody(msg2.body || msg2.text);
  if (b1 === b2) return true;

  // Handle substring containment for truncated notifications vs full MMS
  if (b1.length >= 10 && b2.length >= 10 && (b1.includes(b2) || b2.includes(b1))) return true;

  return false;
}

/**
 * Deduplicates and sorts messages chronologically, merging duplicate payloads.
 */
export function deduplicateMessages(msgList, windowMs = 5000) {
  const sorted = [...msgList].sort((a, b) => Number(a.timestamp || 0) - Number(b.timestamp || 0));
  const deduped = [];

  for (const msg of sorted) {
    let matchedIdx = -1;
    const ts = Number(msg.timestamp || 0);
    for (let i = deduped.length - 1; i >= 0; i--) {
      const prev = deduped[i];
      const prevTs = Number(prev.timestamp || 0);
      if (Math.abs(ts - prevTs) > windowMs + 1000) {
        if (ts - prevTs > windowMs + 1000) break;
      }
      if (isDuplicateMessage(msg, prev, windowMs)) {
        matchedIdx = i;
        break;
      }
    }

    if (matchedIdx >= 0) {
      const prev = deduped[matchedIdx];
      const isRcs = Boolean(prev.isRcs || msg.isRcs);
      const isEncrypted = Boolean(prev.isEncrypted || msg.isEncrypted);
      const protocol = isEncrypted ? 'E2EE_RCS' : (isRcs ? 'RCS' : (prev.protocol || 'SMS'));
      const bestBody = String(msg.body || '').length > String(prev.body || '').length ? msg.body : prev.body;
      const bestAttachData = prev.attachmentData || msg.attachmentData;
      const bestAttachMime = prev.attachmentMime || msg.attachmentMime;

      deduped[matchedIdx] = {
        ...prev,
        body: bestBody,
        attachmentData: bestAttachData,
        attachmentMime: bestAttachMime,
        isRcs,
        isEncrypted,
        protocol,
        isRead: Boolean(prev.isRead || msg.isRead)
      };
    } else {
      deduped.push(msg);
    }
  }

  return deduped;
}

/**
 * Consolidates duplicate and overlapping conversation threads into unified threads.
 */
export function deduplicateAndMergeThreads(threadsInput, selfPhoneDigits = null) {
  const threadsList = Array.isArray(threadsInput) ? threadsInput : Object.values(threadsInput || {});
  const validThreads = threadsList.filter(t => t && typeof t === 'object' && t.threadId !== undefined);
  if (validThreads.length === 0) return {};

  const namedGroupMap = buildNamedGroupMap(validThreads, selfPhoneDigits);
  const contactNameToPhone = buildContactNameToPhoneMap(validThreads, selfPhoneDigits);
  const grouped = {};

  for (const t of validThreads) {
    const key = getCanonicalThreadKey(t, namedGroupMap, contactNameToPhone, selfPhoneDigits);
    grouped[key] = grouped[key] || [];
    grouped[key].push(t);
  }

  const result = {};

  for (const [groupKey, group] of Object.entries(grouped)) {
    if (group.length === 1) {
      const single = group[0];
      const nonSelf = extractNonSelfParticipants(single, selfPhoneDigits);
      if (single.isGroup && nonSelf.length <= 1) {
        const cleanRecip = (single.recipients || []).filter(r => !isSelfOrCarrierToken(r.phone || r.cleanDigits || r, selfPhoneDigits));
        const cleanTitle = (cleanRecip[0] && cleanRecip[0].name) ||
          (cleanRecip[0] && cleanRecip[0].phone) ||
          String(single.contactName || '').split(',')[0].trim() ||
          String(single.address || '').split(',')[0].trim();
        const cleanAddr = (cleanRecip[0] && cleanRecip[0].phone) || String(single.address || '').split(',')[0].trim();
        result[String(single.threadId)] = {
          ...single,
          isGroup: false,
          groupTitle: null,
          contactName: cleanTitle,
          address: cleanAddr,
          recipients: cleanRecip
        };
      } else {
        result[String(single.threadId)] = single;
      }
      continue;
    }

    // Merge multi-thread group
    const stableThreadId = group.map(t => Number(t.threadId))
      .filter(id => id >= 1 && id < 8000000000)
      .sort((a, b) => a - b)[0] || group.map(t => Number(t.threadId)).sort((a, b) => a - b)[0] || Date.now();

    const isEncrypted = group.some(t => t.isEncrypted || t.protocol === 'E2EE_RCS');
    const isRcs = group.some(t => t.isRcs || t.protocol === 'RCS' || isEncrypted);
    const protocol = isEncrypted ? 'E2EE_RCS' : (isRcs ? 'RCS' : 'SMS');

    const nonSelfParts = new Set();
    const combinedRecipients = [];
    const seenRecipPhones = new Set();

    for (const t of group) {
      extractNonSelfParticipants(t, selfPhoneDigits).forEach(p => nonSelfParts.add(p));
      for (const r of (t.recipients || [])) {
        const p = (typeof r === 'object' && r) ? (r.phone || r.cleanDigits || '') : String(r || '');
        if (!isSelfOrCarrierToken(p, selfPhoneDigits)) {
          const d = normalizeDigits(p);
          if (d.length >= 7 && !seenRecipPhones.has(d)) {
            seenRecipPhones.add(d);
            combinedRecipients.push(r);
          }
        }
      }
    }

    const isTrueGroup = nonSelfParts.size > 1;

    const genuineTitle = group.map(t => cleanConversationTitle(t.groupTitle || '')).find(title => {
      return title && !title.includes(',') && !title.includes('&') && !title.includes('@') &&
        !title.startsWith('+') && !title.split('').every(c => (c >= '0' && c <= '9') || ' -().'.includes(c));
    });

    const finalGroupTitle = isTrueGroup
      ? (genuineTitle || combinedRecipients.map(r => r.name || r.phone || '').filter(Boolean).join(', ') || null)
      : null;

    const finalContactName = isTrueGroup
      ? finalGroupTitle
      : (group.map(t => t.contactName).find(n => n && !n.includes(',')) || (combinedRecipients[0] && combinedRecipients[0].name) || group[0].address);

    const finalAddress = isTrueGroup
      ? combinedRecipients.map(r => r.phone || '').filter(Boolean).join(', ')
      : ((combinedRecipients[0] && combinedRecipients[0].phone) || String(group[0].address || '').split(',')[0]);

    const allMessages = [];
    for (const t of group) {
      const msgs = t.messages ? (Array.isArray(t.messages) ? t.messages : Object.values(t.messages)) : [];
      allMessages.push(...msgs);
    }

    const dedupedMessages = deduplicateMessages(allMessages);
    const latestMessage = dedupedMessages[dedupedMessages.length - 1];

    const lastTimestamp = Math.max(
      latestMessage ? Number(latestMessage.timestamp || 0) : 0,
      ...group.map(t => Number(t.lastTimestamp || 0))
    );

    const snippet = (latestMessage && latestMessage.body) ||
      group.map(t => t.snippet).find(s => s && s.trim()) || '';

    const unreadCount = Math.max(0, ...group.map(t => Number(t.unreadCount || 0)));
    const isTyping = group.some(t => Boolean(t.isTyping));
    const typingSender = group.map(t => t.typingSender).find(Boolean) || null;

    const messagesMap = {};
    dedupedMessages.forEach(m => {
      messagesMap[String(m.id || m.timestamp)] = m;
    });

    result[String(stableThreadId)] = {
      threadId: stableThreadId,
      address: finalAddress,
      contactName: finalContactName,
      snippet: snippet,
      lastTimestamp: lastTimestamp,
      unreadCount: unreadCount,
      isGroup: isTrueGroup,
      groupTitle: finalGroupTitle,
      recipients: combinedRecipients,
      messages: messagesMap,
      isRcs: isRcs,
      isEncrypted: isEncrypted,
      protocol: protocol,
      isTyping: isTyping,
      typingSender: typingSender
    };
  }

  return result;
}
