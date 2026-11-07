/**
 * PushBridge v3.0 Popup & Desktop Dashboard Controller
 * Implements Scalable Thread Pagination (30/50/100), In-Memory Quick Text Search,
 * Inline Photo/Media Lightbox, URL OpenGraph Previews, Group Chat Replies,
 * and Multi-Device Peer Presence Monitoring.
 */
import {
  syncGoogleContacts,
  searchCombinedContacts,
  resolveContactIdentity,
  formatPhoneNumber,
  normalizePhoneNumber,
  getInitials
} from '../contacts/google-contacts.js';

// Application State
let currentViewMode = 'bundled'; // 'bundled' | 'chronological'
let currentTargetDeviceId = '';
let activeThreadId = null;
let newChatRecipient = '';
let selectedChatAttachment = null;
let collapsedBundles = new Set();
let phoneIsOnline = true;
let searchAutocomplete = null;
let composeAutocomplete = null;
let currentThreadLimit = 30; // 30 | 50 | 100
let currentSearchQuery = '';
let mediaLightboxInitialized = false;

document.addEventListener('DOMContentLoaded', async () => {
  setupTabs();
  setupGoogleContacts();
  setupNotificationViews();
  setupThreadedSms();
  setupMediaLightbox();
  setupClipboardSync();
  setupWindowExpansion();
  setupGlobalEvents();

  await setupDeviceSelector();
  await checkStreamStatus();
  await checkDevicePresence();
  await restoreSavedState();
  await loadNotifications();
  await loadSmsThreads();
  await loadClipboardState();

  // Periodic heartbeat / presence verification poll every 15 seconds
  setInterval(async () => {
    await checkDevicePresence();
  }, 15000);
});

// =============================================================
// 1. Navigation & State Persistence
// =============================================================
function setupTabs() {
  const tabButtons = document.querySelectorAll('.tab-button');
  const tabPanes = document.querySelectorAll('.tab-pane');

  tabButtons.forEach(btn => {
    btn.addEventListener('click', () => {
      const targetTabId = btn.getAttribute('data-tab');

      tabButtons.forEach(b => {
        b.classList.remove('active', 'text-sky-400', 'border-sky-500', 'font-semibold');
        b.classList.add('text-slate-400', 'border-transparent', 'font-medium');
      });

      btn.classList.add('active', 'text-sky-400', 'border-sky-500', 'font-semibold');
      btn.classList.remove('text-slate-400', 'border-transparent', 'font-medium');

      tabPanes.forEach(pane => {
        if (pane.id === targetTabId) {
          pane.classList.remove('hidden');
        } else {
          pane.classList.add('hidden');
        }
      });
    });
  });
}

async function restoreSavedState() {
  const saved = await chrome.storage.local.get(['notificationViewMode', 'activeTargetDeviceId', 'smsThreadPaginationLimit']);
  if (saved.notificationViewMode) {
    currentViewMode = saved.notificationViewMode;
  }
  if (saved.smsThreadPaginationLimit) {
    currentThreadLimit = Number(saved.smsThreadPaginationLimit) || 30;
    updatePaginationUI(currentThreadLimit);
  }
  updateViewModeToggleUI();
}

function setupWindowExpansion() {
  document.getElementById('btn-expand-tab')?.addEventListener('click', () => {
    chrome.tabs.create({ url: chrome.runtime.getURL('popup/popup.html') });
  });
}

// =============================================================
// 2. Stream & Device Presence Status Monitoring
// =============================================================
async function checkStreamStatus() {
  const statusDot = document.getElementById('status-dot');
  const statusPing = document.getElementById('status-ping');

  try {
    const res = await chrome.runtime.sendMessage({ type: 'CHECK_STREAM' });
    if (res && res.status === 'connected') {
      statusDot.className = 'relative inline-flex rounded-full h-3 w-3 bg-emerald-500';
      statusPing.className = 'animate-ping absolute inline-flex h-full w-full rounded-full bg-emerald-400 opacity-75';
    } else {
      statusDot.className = 'relative inline-flex rounded-full h-3 w-3 bg-amber-500';
      statusPing.className = 'hidden';
    }
  } catch {
    statusDot.className = 'relative inline-flex rounded-full h-3 w-3 bg-rose-500';
    statusPing.className = 'hidden';
  }
}

async function checkDevicePresence() {
  try {
    const res = await chrome.runtime.sendMessage({
      type: 'GET_DEVICE_PRESENCE',
      targetDeviceId: currentTargetDeviceId
    });

    if (res && res.presence) {
      updatePresenceUI(res.presence);
    } else {
      // Default optimistic presence if device selector has an android device
      updatePresenceUI({ online: true, deviceName: currentTargetDeviceId || 'Android Companion' });
    }
  } catch {
    updatePresenceUI({ online: false });
  }
}

function updatePresenceUI(presence) {
  const badge = document.getElementById('phone-presence-badge');
  const dot = document.getElementById('phone-presence-dot');
  const text = document.getElementById('phone-presence-text');
  const offlineBanner = document.getElementById('phone-offline-banner');

  if (!badge || !dot || !text) return;

  // Heartbeat freshness tolerance: within 5 minutes (300,000 ms)
  const isFresh = presence?.lastHeartbeat && (Date.now() - presence.lastHeartbeat < 300000);
  const isOnline = !!presence && (presence.online === true || isFresh);
  phoneIsOnline = isOnline;

  const isTablet = presence?.role === 'tablet' || (currentTargetDeviceId && currentTargetDeviceId.startsWith('tablet_'));
  const deviceLabel = isTablet ? 'Tablet' : 'Phone';

  if (isOnline) {
    badge.className = 'flex items-center gap-1.5 bg-emerald-500/10 border border-emerald-500/20 text-emerald-400 px-2.5 py-0.5 rounded-full text-[11px] font-medium transition cursor-default';
    dot.className = 'w-2 h-2 rounded-full bg-emerald-400 animate-pulse';
    text.textContent = `${deviceLabel} Online`;
    offlineBanner?.classList.add('hidden');
  } else {
    badge.className = 'flex items-center gap-1.5 bg-rose-500/10 border border-rose-500/20 text-rose-400 px-2.5 py-0.5 rounded-full text-[11px] font-medium transition cursor-default';
    dot.className = 'w-2 h-2 rounded-full bg-rose-500';
    text.textContent = `${deviceLabel} Offline`;
    offlineBanner?.classList.remove('hidden');
  }
}

async function setupDeviceSelector() {
  const select = document.getElementById('target-device-select');
  const { activeTargetDeviceId } = await chrome.storage.local.get('activeTargetDeviceId');

  try {
    const res = await chrome.runtime.sendMessage({ type: 'FETCH_DEVICES' });
    const devices = res?.devices || [];

    select.innerHTML = '';
    if (devices.length === 0) {
      select.innerHTML = '<option value="android_companion">Android Phone (Auto)</option>';
      currentTargetDeviceId = 'android_companion';
    } else {
      let matched = false;
      devices.forEach(devId => {
        const opt = document.createElement('option');
        opt.value = devId;
        opt.textContent = devId.startsWith('android') ? `📱 ${devId}` : `💻 ${devId}`;
        if (devId === activeTargetDeviceId) {
          opt.selected = true;
          currentTargetDeviceId = devId;
          matched = true;
        }
        select.appendChild(opt);
      });
      if (!matched && devices.length > 0) {
        // Prioritize selecting an android companion device
        const androidDev = devices.find(d => d.startsWith('android')) || devices[0];
        select.value = androidDev;
        currentTargetDeviceId = androidDev;
        await chrome.storage.local.set({ activeTargetDeviceId: androidDev });
      }
    }
  } catch {
    select.innerHTML = '<option value="android_companion">Android Companion</option>';
    currentTargetDeviceId = 'android_companion';
  }

  select.addEventListener('change', async (e) => {
    currentTargetDeviceId = e.target.value;
    await chrome.storage.local.set({ activeTargetDeviceId: currentTargetDeviceId });
    await checkDevicePresence();
    await loadSmsThreads();
  });
}

// =============================================================
// 3. Google Contacts Integration
// =============================================================
function setupGoogleContacts() {
  const btnAuth = document.getElementById('btn-google-auth');
  const authText = document.getElementById('google-auth-text');
  const indicator = document.getElementById('google-auth-indicator');

  chrome.storage.local.get(['googleContactsCache']).then(({ googleContactsCache = [] }) => {
    if (googleContactsCache.length > 0) {
      authText.textContent = `Contacts (${googleContactsCache.length})`;
      indicator.className = 'w-2 h-2 rounded-full bg-emerald-400';
    }
  });

  btnAuth?.addEventListener('click', async () => {
    authText.textContent = 'Syncing...';
    indicator.className = 'w-2 h-2 rounded-full bg-amber-400 animate-pulse';

    const result = await syncGoogleContacts(true);
    if (result.success) {
      authText.textContent = `Contacts (${result.contacts.length})`;
      indicator.className = 'w-2 h-2 rounded-full bg-emerald-400';
      showToast(`✓ Synced ${result.contacts.length} Google Contacts`);
    } else {
      authText.textContent = `Contacts (${result.contacts?.length || 0})`;
      indicator.className = 'w-2 h-2 rounded-full bg-sky-400';
      showToast(`Google Contacts ready (Cache: ${result.contacts?.length || 0})`);
    }
    await loadSmsThreads();
  });
}

// =============================================================
// 4. Flexible Notification Views (Bundled vs Chronological)
// =============================================================
function setupNotificationViews() {
  const btnBundled = document.getElementById('btn-view-bundled');
  const btnChronological = document.getElementById('btn-view-chronological');
  const btnClearFeed = document.getElementById('btn-clear-feed');
  const btnEmptyTest = document.getElementById('btn-empty-test');

  btnBundled?.addEventListener('click', async () => {
    currentViewMode = 'bundled';
    await chrome.storage.local.set({ notificationViewMode: 'bundled' });
    updateViewModeToggleUI();
    await loadNotifications();
  });

  btnChronological?.addEventListener('click', async () => {
    currentViewMode = 'chronological';
    await chrome.storage.local.set({ notificationViewMode: 'chronological' });
    updateViewModeToggleUI();
    await loadNotifications();
  });

  btnClearFeed?.addEventListener('click', async () => {
    await chrome.storage.local.set({ notificationHistory: [] });
    await loadNotifications();
  });

  btnEmptyTest?.addEventListener('click', async () => {
    await chrome.runtime.sendMessage({ type: 'TEST_NOTIFICATION_ALERT' });
    await loadNotifications();
  });
}

function updateViewModeToggleUI() {
  const btnBundled = document.getElementById('btn-view-bundled');
  const btnChronological = document.getElementById('btn-view-chronological');

  if (currentViewMode === 'bundled') {
    btnBundled.className = 'px-2.5 py-1 rounded-md text-xs font-semibold bg-sky-600 text-white transition flex items-center gap-1.5 shadow-sm';
    btnChronological.className = 'px-2.5 py-1 rounded-md text-xs font-medium text-slate-400 hover:text-slate-200 transition flex items-center gap-1.5';
  } else {
    btnChronological.className = 'px-2.5 py-1 rounded-md text-xs font-semibold bg-sky-600 text-white transition flex items-center gap-1.5 shadow-sm';
    btnBundled.className = 'px-2.5 py-1 rounded-md text-xs font-medium text-slate-400 hover:text-slate-200 transition flex items-center gap-1.5';
  }
}

async function loadNotifications() {
  const container = document.getElementById('notifications-container');
  const emptyState = document.getElementById('feed-empty-state');
  const counter = document.getElementById('feed-counter');
  const countLabel = document.getElementById('notif-count-label');

  const { notificationHistory = [] } = await chrome.storage.local.get('notificationHistory');
  counter.textContent = notificationHistory.length;
  countLabel.textContent = `${notificationHistory.length} notification${notificationHistory.length === 1 ? '' : 's'}`;

  if (notificationHistory.length === 0) {
    container.innerHTML = '';
    emptyState.classList.remove('hidden');
    return;
  }

  emptyState.classList.add('hidden');
  container.innerHTML = '';

  if (currentViewMode === 'bundled') {
    renderBundledNotifications(container, notificationHistory);
  } else {
    renderChronologicalNotifications(container, notificationHistory);
  }
}

function renderBundledNotifications(container, notifications) {
  const groups = new Map();

  notifications.forEach(item => {
    const key = item.appName || item.packageName || 'General Alerts';
    if (!groups.has(key)) {
      groups.set(key, {
        appName: key,
        packageName: item.packageName || '',
        items: []
      });
    }
    groups.get(key).items.push(item);
  });

  groups.forEach((group, appName) => {
    const isCollapsed = collapsedBundles.has(appName);
    const bundleCard = document.createElement('div');
    bundleCard.className = 'rounded-xl border border-slate-800 bg-slate-900/70 overflow-hidden shadow-sm';

    const badgeColor = getAppBadgeStyle(appName, group.packageName);

    const header = document.createElement('div');
    header.className = 'p-3 flex items-center justify-between bg-slate-900/90 hover:bg-slate-800/80 cursor-pointer border-b border-slate-800/60 transition select-none';
    header.innerHTML = `
      <div class="flex items-center gap-2.5">
        <span class="transform transition-transform text-xs text-slate-400 ${isCollapsed ? '-rotate-90' : 'rotate-0'}">▼</span>
        <span class="text-xs font-bold text-slate-100 flex items-center gap-2">
          ${escapeHtml(appName)}
        </span>
        <span class="text-[10px] font-semibold px-2 py-0.2 rounded-full border ${badgeColor}">
          ${group.items.length} unread
        </span>
      </div>
      <div class="flex items-center gap-2">
        <button class="btn-dismiss-bundle text-[11px] text-slate-500 hover:text-rose-400 transition cursor-pointer px-1.5 py-0.5 rounded" title="Dismiss all notifications from ${escapeHtml(appName)}">
          Dismiss All
        </button>
      </div>
    `;

    header.addEventListener('click', (e) => {
      if (e.target.closest('.btn-dismiss-bundle')) return;
      if (collapsedBundles.has(appName)) {
        collapsedBundles.delete(appName);
      } else {
        collapsedBundles.add(appName);
      }
      loadNotifications();
    });

    header.querySelector('.btn-dismiss-bundle')?.addEventListener('click', async (e) => {
      e.stopPropagation();
      await dismissGroup(group.items.map(i => i.id));
    });

    bundleCard.appendChild(header);

    if (!isCollapsed) {
      const itemsContainer = document.createElement('div');
      itemsContainer.className = 'divide-y divide-slate-800/50 p-2 space-y-2';

      group.items.forEach(item => {
        const itemCard = createNotificationItemElement(item, false);
        itemsContainer.appendChild(itemCard);
      });

      bundleCard.appendChild(itemsContainer);
    }

    container.appendChild(bundleCard);
  });
}

function renderChronologicalNotifications(container, notifications) {
  const sorted = [...notifications].sort((a, b) => b.timestamp - a.timestamp);

  sorted.forEach(item => {
    const card = createNotificationItemElement(item, true);
    container.appendChild(card);
  });
}

function createNotificationItemElement(item, showAppBadge = true) {
  const card = document.createElement('div');
  card.className = 'rounded-lg border border-slate-800/80 bg-slate-900/90 p-3 space-y-2 transition hover:border-slate-700';

  const timeStr = formatTimestamp(item.timestamp);
  const badgeColor = getAppBadgeStyle(item.appName, item.packageName);

  card.innerHTML = `
    <div class="flex items-center justify-between">
      <div class="flex items-center gap-2">
        ${showAppBadge ? `
          <span class="text-[10px] font-semibold px-2 py-0.5 rounded-full border ${badgeColor}">
            ${escapeHtml(item.appName)}
          </span>
        ` : ''}
        ${item.subText ? `<span class="text-[10px] text-slate-500 font-medium truncate max-w-[160px]">${escapeHtml(item.subText)}</span>` : ''}
      </div>
      <div class="flex items-center gap-2">
        <span class="text-[10px] text-slate-500">${timeStr}</span>
        <button class="btn-dismiss-item text-slate-500 hover:text-rose-400 p-0.5 text-xs transition cursor-pointer" title="Dismiss">✕</button>
      </div>
    </div>

    <div>
      <h3 class="text-xs font-bold text-slate-100">${escapeHtml(item.title)}</h3>
      <p class="text-xs text-slate-300 mt-0.5 leading-relaxed break-words">${escapeHtml(item.text)}</p>
    </div>

    <!-- Inline Reply -->
    <div class="pt-1">
      ${item.canReply ? `
        <form class="inline-reply-form flex items-center gap-2 mt-1">
          <input type="text" placeholder="Type quick reply to ${escapeHtml(item.title)}..." required
            class="reply-input flex-1 bg-slate-950 border border-slate-700 rounded-lg px-2.5 py-1.5 text-xs text-white placeholder-slate-500 focus:outline-none focus:border-sky-500">
          <button type="submit" class="bg-sky-600 hover:bg-sky-500 active:bg-sky-700 text-white rounded-lg px-3 py-1.5 text-xs font-semibold flex items-center gap-1 transition cursor-pointer shrink-0">
            <span>Reply</span>
          </button>
        </form>
        <div class="reply-feedback text-[11px] font-medium hidden mt-1"></div>
      ` : `
        <span class="text-[10px] text-slate-500 italic">Notification only</span>
      `}
    </div>
  `;

  card.querySelector('.btn-dismiss-item')?.addEventListener('click', async () => {
    await dismissNotification(item.id);
  });

  const replyForm = card.querySelector('.inline-reply-form');
  if (replyForm) {
    replyForm.addEventListener('submit', async (e) => {
      e.preventDefault();
      const input = replyForm.querySelector('.reply-input');
      const submitBtn = replyForm.querySelector('button');
      const feedback = card.querySelector('.reply-feedback');
      const replyText = input.value.trim();

      if (!replyText) return;
      submitBtn.disabled = true;
      submitBtn.textContent = 'Sending...';

      try {
        const response = await chrome.runtime.sendMessage({
          type: 'SEND_REMOTE_REPLY',
          targetDeviceId: item.sourceDeviceId || currentTargetDeviceId,
          notificationKey: item.notificationKey,
          replyText
        });

        if (response && response.success) {
          feedback.textContent = `✓ Reply sent to ${item.appName}`;
          feedback.className = 'reply-feedback text-[11px] font-medium text-emerald-400 mt-1';
          input.value = '';
          setTimeout(() => feedback.classList.add('hidden'), 3500);
        } else {
          feedback.textContent = `✗ Failed: ${response?.error || 'Could not dispatch'}`;
          feedback.className = 'reply-feedback text-[11px] font-medium text-rose-400 mt-1';
        }
      } catch (err) {
        feedback.textContent = `✗ Error: ${err.message}`;
        feedback.className = 'reply-feedback text-[11px] font-medium text-rose-400 mt-1';
      } finally {
        submitBtn.disabled = false;
        submitBtn.textContent = 'Reply';
      }
    });
  }

  return card;
}

async function dismissNotification(id) {
  const { notificationHistory = [] } = await chrome.storage.local.get('notificationHistory');
  const updated = notificationHistory.filter(n => n.id !== id);
  await chrome.storage.local.set({ notificationHistory: updated });
  await loadNotifications();
}

async function dismissGroup(ids) {
  const idSet = new Set(ids);
  const { notificationHistory = [] } = await chrome.storage.local.get('notificationHistory');
  const updated = notificationHistory.filter(n => !idSet.has(n.id));
  await chrome.storage.local.set({ notificationHistory: updated });
  await loadNotifications();
}

function getAppBadgeStyle(appName, pkg) {
  const name = (appName || pkg || '').toLowerCase();
  if (name.includes('whatsapp')) return 'bg-emerald-500/10 text-emerald-400 border-emerald-500/20';
  if (name.includes('telegram')) return 'bg-sky-500/10 text-sky-400 border-sky-500/20';
  if (name.includes('slack')) return 'bg-purple-500/10 text-purple-400 border-purple-500/20';
  if (name.includes('signal')) return 'bg-blue-500/10 text-blue-400 border-blue-500/20';
  if (name.includes('gmail') || name.includes('mail')) return 'bg-rose-500/10 text-rose-400 border-rose-500/20';
  if (name.includes('sms') || name.includes('message')) return 'bg-amber-500/10 text-amber-400 border-amber-500/20';
  return 'bg-slate-700/30 text-slate-300 border-slate-700/50';
}

// =============================================================
// 5. Robust Contact Autocomplete Component (Keyboard Accessible)
// =============================================================
class ContactAutocomplete {
  constructor(inputEl, popoverEl, onSelect) {
    this.input = inputEl;
    this.popover = popoverEl;
    this.onSelect = onSelect;
    this.matches = [];
    this.activeIndex = -1;
    this.debounceTimer = null;

    this.bindEvents();
  }

  bindEvents() {
    this.input.addEventListener('input', () => {
      clearTimeout(this.debounceTimer);
      this.debounceTimer = setTimeout(() => this.handleQuery(), 100);
    });

    this.input.addEventListener('keydown', (e) => this.handleKeyDown(e));

    document.addEventListener('click', (e) => {
      if (!this.popover.contains(e.target) && e.target !== this.input) {
        this.hide();
      }
    });
  }

  async handleQuery() {
    const q = this.input.value.trim();
    if (!q) {
      this.hide();
      return;
    }
    this.matches = await searchCombinedContacts(q);
    this.activeIndex = -1;
    this.render();
  }

  handleKeyDown(e) {
    if (this.popover.classList.contains('hidden') || this.matches.length === 0) {
      if (e.key === 'ArrowDown' && this.input.value.trim()) {
        this.handleQuery();
      }
      return;
    }

    switch (e.key) {
      case 'ArrowDown':
        e.preventDefault();
        this.activeIndex = (this.activeIndex + 1) % this.matches.length;
        this.updateActiveHighlight();
        break;

      case 'ArrowUp':
        e.preventDefault();
        this.activeIndex = (this.activeIndex - 1 + this.matches.length) % this.matches.length;
        this.updateActiveHighlight();
        break;

      case 'Enter':
        if (this.activeIndex >= 0 && this.activeIndex < this.matches.length) {
          e.preventDefault();
          this.choose(this.matches[this.activeIndex]);
        }
        break;

      case 'Escape':
        e.preventDefault();
        this.hide();
        break;
    }
  }

  render() {
    this.popover.innerHTML = '';
    if (this.matches.length === 0) {
      this.popover.innerHTML = '<div class="p-3 text-xs text-slate-500 text-center">No matching contacts found.</div>';
      this.popover.classList.remove('hidden');
      return;
    }

    this.matches.forEach((contact, idx) => {
      const item = document.createElement('div');
      item.className = 'autocomplete-item p-2 flex items-center gap-2.5 hover:bg-slate-800 cursor-pointer transition select-none';
      item.setAttribute('data-index', idx);

      const sourceBadge = contact.source === 'google'
        ? '<span class="text-[9px] bg-sky-500/10 text-sky-400 border border-sky-500/20 px-1 rounded">Google</span>'
        : '<span class="text-[9px] bg-emerald-500/10 text-emerald-400 border border-emerald-500/20 px-1 rounded">Phone</span>';

      item.innerHTML = `
        <div class="w-7 h-7 rounded-full bg-slate-700 flex items-center justify-center font-bold text-xs text-sky-300 shrink-0 overflow-hidden">
          ${contact.photoUrl ? `<img src="${contact.photoUrl}" class="w-full h-full object-cover">` : escapeHtml(contact.initials)}
        </div>
        <div class="flex-1 min-w-0">
          <div class="flex items-center gap-1.5">
            <p class="text-xs font-semibold text-slate-100 truncate">${escapeHtml(contact.name)}</p>
            ${sourceBadge}
          </div>
          <p class="text-[11px] text-slate-400 font-mono truncate">${escapeHtml(formatPhoneNumber(contact.phone))} (${contact.type || 'mobile'})</p>
        </div>
      `;

      item.addEventListener('mouseenter', () => {
        this.activeIndex = idx;
        this.updateActiveHighlight();
      });

      item.addEventListener('click', () => {
        this.choose(contact);
      });

      this.popover.appendChild(item);
    });

    this.popover.classList.remove('hidden');
  }

  updateActiveHighlight() {
    const items = this.popover.querySelectorAll('.autocomplete-item');
    items.forEach((item, idx) => {
      if (idx === this.activeIndex) {
        item.classList.add('bg-slate-800', 'ring-1', 'ring-sky-500/60');
        item.scrollIntoView({ block: 'nearest' });
      } else {
        item.classList.remove('bg-slate-800', 'ring-1', 'ring-sky-500/60');
      }
    });
  }

  choose(contact) {
    this.hide();
    if (this.onSelect) {
      this.onSelect(contact);
    }
  }

  hide() {
    this.popover.classList.add('hidden');
    this.activeIndex = -1;
  }
}

// =============================================================
// 6. Threaded SMS & Group Chat Interface
// =============================================================
function setupThreadedSms() {
  const searchInput = document.getElementById('sms-search-input');
  const searchPopover = document.getElementById('contacts-autocomplete-popover');
  const btnClearSearch = document.getElementById('btn-clear-sms-search');
  const feedbackStrip = document.getElementById('sms-search-feedback-strip');
  const btnNewChat = document.getElementById('btn-new-chat');
  const btnManualSync = document.getElementById('btn-manual-sync-sms');
  const btnSyncThreads = document.getElementById('btn-sync-threads');
  const composerForm = document.getElementById('chat-composer-form');
  const composerInput = document.getElementById('chat-composer-input');
  const fileInput = document.getElementById('chat-file-input');
  const btnAttach = document.getElementById('btn-attach-chat-file');
  const attachmentPreview = document.getElementById('chat-attachment-preview');
  const removeAttachmentBtn = document.getElementById('btn-remove-chat-attachment');
  const newRecipientInput = document.getElementById('new-recipient-input');
  const composePopover = document.getElementById('compose-autocomplete-popover');

  // 1. Scalable Thread Pagination Limit Controls (30 / 50 / 100)
  const btnLimit30 = document.getElementById('btn-limit-30');
  const btnLimit50 = document.getElementById('btn-limit-50');
  const btnLimit100 = document.getElementById('btn-limit-100');

  [btnLimit30, btnLimit50, btnLimit100].forEach(btn => {
    btn?.addEventListener('click', async () => {
      const limit = Number(btn.getAttribute('data-limit')) || 30;
      updatePaginationUI(limit);
      await chrome.storage.local.set({ smsThreadPaginationLimit: limit });
      await loadSmsThreads();
      await triggerSmsSync(limit);
    });
  });

  // 2. In-Memory Quick Text Search across all loaded message bodies
  let searchDebounceTimer = null;
  searchInput?.addEventListener('input', () => {
    clearTimeout(searchDebounceTimer);
    searchDebounceTimer = setTimeout(async () => {
      currentSearchQuery = searchInput.value.trim();
      if (currentSearchQuery) {
        btnClearSearch?.classList.remove('hidden');
        feedbackStrip?.classList.remove('hidden');
      } else {
        btnClearSearch?.classList.add('hidden');
        feedbackStrip?.classList.add('hidden');
      }
      await loadSmsThreads();
    }, 50);
  });

  btnClearSearch?.addEventListener('click', async () => {
    if (searchInput) searchInput.value = '';
    currentSearchQuery = '';
    btnClearSearch?.classList.add('hidden');
    feedbackStrip?.classList.add('hidden');
    await loadSmsThreads();
  });

  // 3. Sidebar Search Autocomplete (for quick contact discovery)
  searchAutocomplete = new ContactAutocomplete(searchInput, searchPopover, async (contact) => {
    if (searchInput) searchInput.value = '';
    currentSearchQuery = '';
    btnClearSearch?.classList.add('hidden');
    feedbackStrip?.classList.add('hidden');

    const { smsThreads = {} } = await chrome.storage.local.get('smsThreads');
    const targetDigits = contact.cleanDigits || normalizePhoneNumber(contact.phone);

    // Look for matching existing thread
    const existingThread = Object.values(smsThreads).find(t => {
      const addrDigits = normalizePhoneNumber(t.address);
      return addrDigits.includes(targetDigits) || targetDigits.includes(addrDigits);
    });

    if (existingThread) {
      activeThreadId = String(existingThread.threadId);
      document.getElementById('new-recipient-bar')?.classList.add('hidden');
      await loadSmsThreads();
    } else {
      startNewConversation(contact.phone, contact.name);
    }
  });

  // 4. Compose View Recipient Autocomplete
  composeAutocomplete = new ContactAutocomplete(newRecipientInput, composePopover, (contact) => {
    newRecipientInput.value = contact.phone;
    newChatRecipient = contact.phone;
    document.getElementById('chat-header-name').textContent = contact.name;
    document.getElementById('chat-header-phone').textContent = formatPhoneNumber(contact.phone);
    composerInput.focus();
  });

  // New Chat action
  btnNewChat?.addEventListener('click', () => {
    startNewConversation();
  });

  // Manual SMS Sync from phone
  btnManualSync?.addEventListener('click', () => triggerSmsSync(currentThreadLimit));
  btnSyncThreads?.addEventListener('click', () => triggerSmsSync(currentThreadLimit));

  // File attachment handler
  btnAttach?.addEventListener('click', () => fileInput.click());
  fileInput?.addEventListener('change', () => {
    if (fileInput.files.length > 0) {
      const file = fileInput.files[0];
      const reader = new FileReader();
      reader.onload = () => {
        selectedChatAttachment = {
          name: file.name,
          type: file.type || 'application/octet-stream',
          size: file.size,
          base64: reader.result
        };
        document.getElementById('chat-attachment-name').textContent = file.name;
        document.getElementById('chat-attachment-size').textContent = formatBytes(file.size);
        attachmentPreview.classList.remove('hidden');
      };
      reader.readAsDataURL(file);
    }
  });

  removeAttachmentBtn?.addEventListener('click', () => {
    selectedChatAttachment = null;
    fileInput.value = '';
    attachmentPreview.classList.add('hidden');
  });

  // Multi-line composer auto-height & enter-to-send
  composerInput?.addEventListener('keydown', (e) => {
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault();
      composerForm.dispatchEvent(new Event('submit'));
    }
  });

  // Submit chat message (supports 1-on-1 and multi-recipient group broadcasts)
  composerForm?.addEventListener('submit', async (e) => {
    e.preventDefault();
    const text = composerInput.value.trim();
    if (!text && !selectedChatAttachment) return;

    let targetRecipient = '';
    let targetThreadId = activeThreadId;
    let isGroup = false;
    let recipientsList = [];

    const { smsThreads = {} } = await chrome.storage.local.get('smsThreads');
    const thread = smsThreads[String(activeThreadId)];

    if (activeThreadId === 'new_chat') {
      targetRecipient = newRecipientInput.value.trim();
      if (!targetRecipient) {
        showToast('Please enter a recipient phone number or contact.');
        newRecipientInput.focus();
        return;
      }
      // Check if recipient contains multiple numbers (group compose)
      if (targetRecipient.includes(',') || targetRecipient.includes(';')) {
        isGroup = true;
        recipientsList = targetRecipient.split(/[,;]+/).map(s => s.trim()).filter(Boolean);
      }
    } else {
      if (!thread) {
        showToast('No active conversation selected.');
        return;
      }
      targetRecipient = thread.address;
      isGroup = !!thread.isGroup || (thread.recipients && thread.recipients.length > 1);
      recipientsList = (thread.recipients || []).map(r => r.phone || r.cleanDigits || r);
    }

    const payloadAttachment = selectedChatAttachment;

    composerInput.value = '';
    selectedChatAttachment = null;
    fileInput.value = '';
    attachmentPreview.classList.add('hidden');

    if (!phoneIsOnline) {
      showToast('⚠️ Phone is offline. Outbound message queued for delivery on reconnect.');
    }

    try {
      const res = await chrome.runtime.sendMessage({
        type: 'SEND_CHAT_MESSAGE',
        targetDeviceId: currentTargetDeviceId || 'android_companion',
        threadId: targetThreadId !== 'new_chat' ? targetThreadId : 0,
        recipient: targetRecipient,
        isGroup,
        recipients: recipientsList,
        messageBody: text,
        attachment: payloadAttachment,
        attachmentData: payloadAttachment?.base64 || null,
        attachmentMime: payloadAttachment?.type || null
      });

      if (res && res.success) {
        if (activeThreadId === 'new_chat' && res.threadKey) {
          activeThreadId = res.threadKey;
          document.getElementById('new-recipient-bar')?.classList.add('hidden');
        }
        await loadSmsThreads();
        await renderActiveConversation();
      } else {
        showToast('Failed to dispatch message to phone.');
      }
    } catch (err) {
      showToast(`Error sending message: ${err.message}`);
    }
  });
}

function updatePaginationUI(limit) {
  currentThreadLimit = limit;
  const btn30 = document.getElementById('btn-limit-30');
  const btn50 = document.getElementById('btn-limit-50');
  const btn100 = document.getElementById('btn-limit-100');
  const badge = document.getElementById('active-threads-limit-badge');

  [btn30, btn50, btn100].forEach(b => {
    if (!b) return;
    const isThis = Number(b.getAttribute('data-limit')) === limit;
    b.className = isThis
      ? 'thread-limit-btn px-2 py-0.5 rounded text-[10px] font-semibold bg-sky-600 text-white transition cursor-pointer'
      : 'thread-limit-btn px-2 py-0.5 rounded text-[10px] font-medium text-slate-400 hover:text-white transition cursor-pointer';
  });

  if (badge) {
    badge.textContent = `${limit} limit`;
  }
}

async function triggerSmsSync(limit = currentThreadLimit) {
  showToast(`Requesting top ${limit} SMS threads from Android phone...`);
  try {
    await chrome.runtime.sendMessage({
      type: 'TRIGGER_SMS_SYNC',
      targetDeviceId: currentTargetDeviceId || 'android_companion',
      limit: Number(limit) || 30
    });
    setTimeout(async () => {
      await loadSmsThreads();
      showToast(`✓ Refreshed ${limit} threads from phone`);
    }, 2000);
  } catch (err) {
    showToast(`Sync error: ${err.message}`);
  }
}

function startNewConversation(recipientPhone = '', recipientName = '') {
  activeThreadId = 'new_chat';
  newChatRecipient = recipientPhone;

  const headerAvatar = document.getElementById('chat-header-avatar');
  const headerName = document.getElementById('chat-header-name');
  const headerPhone = document.getElementById('chat-header-phone');
  const groupBadge = document.getElementById('chat-group-badge');
  const participantsContainer = document.getElementById('chat-group-participants');
  const broadcastNotice = document.getElementById('chat-group-broadcast-notice');
  const recipientBar = document.getElementById('new-recipient-bar');
  const recipientInput = document.getElementById('new-recipient-input');
  const container = document.getElementById('chat-messages-container');

  headerAvatar.textContent = '+';
  headerName.textContent = recipientName || 'New Conversation';
  headerPhone.textContent = recipientPhone ? formatPhoneNumber(recipientPhone) : 'Type recipient name or number below';
  groupBadge.classList.add('hidden');
  participantsContainer.classList.add('hidden');
  broadcastNotice.classList.add('hidden');

  recipientBar.classList.remove('hidden');
  recipientInput.value = recipientPhone;
  recipientInput.focus();

  container.innerHTML = `
    <div class="h-full flex flex-col items-center justify-center text-center p-6 text-slate-500">
      <div class="w-12 h-12 rounded-full bg-slate-900 border border-slate-800 flex items-center justify-center text-xl mb-2 text-slate-400">
        ✉️
      </div>
      <p class="text-xs font-semibold text-slate-300">Start New Message</p>
      <p class="text-[11px] text-slate-500 mt-1 max-w-xs">Type a contact name, phone number, or comma-separated numbers for a group text.</p>
    </div>
  `;

  document.querySelectorAll('.thread-item-card').forEach(el => el.classList.remove('bg-slate-800', 'border-sky-500'));
}

async function loadSmsThreads() {
  const container = document.getElementById('thread-list-container');
  const counter = document.getElementById('threads-counter');
  const summaryText = document.getElementById('threads-summary-text');

  let { smsThreads = {}, smsThreadPaginationLimit } = await chrome.storage.local.get(['smsThreads', 'smsThreadPaginationLimit']);
  if (smsThreadPaginationLimit) {
    currentThreadLimit = Number(smsThreadPaginationLimit) || 30;
    updatePaginationUI(currentThreadLimit);
  }

  let allThreadsList = Object.values(smsThreads);

  if (allThreadsList.length === 0) {
    try {
      const res = await chrome.runtime.sendMessage({
        type: 'FETCH_SMS_THREADS',
        targetDeviceId: currentTargetDeviceId || 'android_companion',
        limit: currentThreadLimit
      });
      if (res && res.threads) {
        smsThreads = res.threads;
        allThreadsList = Object.values(smsThreads);
      }
    } catch {}
  }

  // Fallback demo conversations if empty so interface displays immediately
  if (allThreadsList.length === 0) {
    allThreadsList = getFallbackSmsThreads();
  }

  // In-Memory Quick Text Search: real-time filtering across contacts, numbers, snippets, and all loaded message bodies
  let filteredList = allThreadsList;
  let matchCount = 0;

  if (currentSearchQuery) {
    const qLower = currentSearchQuery.toLowerCase();
    filteredList = allThreadsList.filter(t => {
      const nameMatch = (t.contactName || '').toLowerCase().includes(qLower) ||
                        (t.groupTitle || '').toLowerCase().includes(qLower);
      const addrMatch = (t.address || '').toLowerCase().includes(qLower) ||
                        normalizePhoneNumber(t.address || '').includes(normalizePhoneNumber(currentSearchQuery));
      const snippetMatch = (t.snippet || '').toLowerCase().includes(qLower);
      const msgs = t.messages ? Object.values(t.messages) : [];
      const msgMatch = msgs.some(m => (m.body || '').toLowerCase().includes(qLower) ||
                                      (m.contactName || '').toLowerCase().includes(qLower));
      const matched = nameMatch || addrMatch || snippetMatch || msgMatch;
      if (matched) matchCount++;
      return matched;
    });

    const matchCountEl = document.getElementById('sms-search-match-count');
    if (matchCountEl) {
      matchCountEl.textContent = `${matchCount} conversation${matchCount === 1 ? '' : 's'} matched`;
    }
  }

  // Sort newest first
  filteredList.sort((a, b) => (b.lastTimestamp || 0) - (a.lastTimestamp || 0));

  // Scalable Thread Pagination (30 / 50 / 100)
  const paginatedList = filteredList.slice(0, currentThreadLimit);

  if (counter) counter.textContent = filteredList.length;
  if (summaryText) summaryText.textContent = `${paginatedList.length} of ${filteredList.length} conversation${filteredList.length === 1 ? '' : 's'}`;

  container.innerHTML = '';
  if (paginatedList.length === 0) {
    container.innerHTML = `
      <div class="p-6 text-center text-slate-500 text-xs">
        ${currentSearchQuery ? `No conversations matching "<span class="text-amber-300 font-medium">${escapeHtml(currentSearchQuery)}</span>"` : 'No conversations found.'}
      </div>
    `;
  } else {
    for (const thread of paginatedList) {
      const card = await createThreadListItem(thread);
      container.appendChild(card);
    }
  }

  // Select first thread by default if none selected or if activeThreadId is no longer present
  if ((!activeThreadId || !filteredList.some(t => String(t.threadId) === String(activeThreadId))) && filteredList.length > 0) {
    activeThreadId = String(filteredList[0].threadId);
  }

  await renderActiveConversation();
}

/**
 * Requirement 1 & 2: Renders thread list item with full contact resolution,
 * avatar/initials, group chat indicator, and keyword search highlighting.
 */
async function createThreadListItem(thread) {
  const item = document.createElement('div');
  const isSelected = String(thread.threadId) === String(activeThreadId);
  item.className = `thread-item-card p-3 flex items-center gap-2.5 cursor-pointer transition border-l-2 select-none hover:bg-slate-800/60 ${
    isSelected ? 'bg-slate-800 border-sky-500' : 'border-transparent'
  }`;

  const {
    googleContactsCache = [],
    deviceContactsCache = {}
  } = await chrome.storage.local.get(['googleContactsCache', 'deviceContactsCache']);

  const deviceList = Array.isArray(deviceContactsCache)
    ? deviceContactsCache
    : Object.values(deviceContactsCache);

  const isGroup = !!thread.isGroup || (thread.recipients && thread.recipients.length > 1);

  let displayName = '';
  let avatarHtml = '';

  if (isGroup) {
    // Group conversation
    const recips = thread.recipients || [];
    if (thread.groupTitle && thread.groupTitle.trim()) {
      displayName = thread.groupTitle;
    } else if (recips.length > 0) {
      displayName = recips.map(r => {
        const iden = resolveContactIdentity(r.phone || r.cleanDigits || r, googleContactsCache, deviceList);
        return iden.name;
      }).join(', ');
    } else {
      displayName = formatPhoneNumber(thread.address) || 'Group Chat';
    }

    avatarHtml = `
      <div class="w-9 h-9 rounded-full bg-gradient-to-tr from-purple-600 to-indigo-600 flex items-center justify-center text-white text-sm font-bold shrink-0 shadow-sm">
        👥
      </div>
    `;
  } else {
    // 1-on-1 SMS conversation: Resolve identity from Google & device contacts
    const identity = resolveContactIdentity(thread.address, googleContactsCache, deviceList);

    if (thread.contactName && !thread.contactName.match(/^[+\d\s()-]+$/)) {
      displayName = thread.contactName;
    } else {
      displayName = identity.name;
    }

    if (identity.avatarUrl) {
      avatarHtml = `
        <div class="w-9 h-9 rounded-full overflow-hidden border border-slate-700 shrink-0 shadow-sm">
          <img src="${identity.avatarUrl}" class="w-full h-full object-cover">
        </div>
      `;
    } else {
      avatarHtml = `
        <div class="w-9 h-9 rounded-full bg-gradient-to-tr from-sky-600 to-indigo-600 flex items-center justify-center text-white text-xs font-bold shrink-0 shadow-sm">
          ${escapeHtml(identity.initials)}
        </div>
      `;
    }
  }

  const timeStr = formatTimestamp(thread.lastTimestamp);

  // Apply real-time search keyword highlighting
  const renderedName = currentSearchQuery ? highlightKeywords(escapeHtml(displayName), currentSearchQuery) : escapeHtml(displayName);
  const snippetText = thread.snippet || 'No messages';
  const renderedSnippet = currentSearchQuery ? highlightKeywords(escapeHtml(snippetText), currentSearchQuery) : escapeHtml(snippetText);

  item.innerHTML = `
    <!-- Contact Avatar -->
    ${avatarHtml}

    <!-- Info & Snippet -->
    <div class="flex-1 min-w-0">
      <div class="flex items-center justify-between mb-0.5">
        <h3 class="text-xs font-bold text-slate-100 truncate">${renderedName}</h3>
        <span class="text-[10px] text-slate-500 shrink-0 ml-1">${timeStr}</span>
      </div>
      <div class="flex items-center justify-between">
        <p class="text-[11px] text-slate-400 truncate flex-1">${renderedSnippet}</p>
        ${isGroup ? `<span class="text-[9px] bg-purple-500/20 text-purple-300 border border-purple-500/30 px-1 py-0.2 rounded ml-1 font-medium shrink-0">Group</span>` : ''}
      </div>
    </div>

    ${thread.unreadCount > 0 ? `
      <span class="bg-sky-500 text-white text-[10px] font-bold px-1.5 py-0.2 rounded-full shrink-0 ml-1">
        ${thread.unreadCount}
      </span>
    ` : ''}
  `;

  item.addEventListener('click', () => {
    activeThreadId = String(thread.threadId);
    document.getElementById('new-recipient-bar')?.classList.add('hidden');
    document.querySelectorAll('.thread-item-card').forEach(el => el.classList.remove('bg-slate-800', 'border-sky-500'));
    item.classList.add('bg-slate-800', 'border-sky-500');
    renderActiveConversation();
  });

  return item;
}

/**
 * Requirement 1 & 2: Renders active chat with group details, participant chips,
 * and sender resolution for each bubble.
 */
async function renderActiveConversation() {
  if (activeThreadId === 'new_chat') return;

  const headerAvatar = document.getElementById('chat-header-avatar');
  const headerName = document.getElementById('chat-header-name');
  const headerPhone = document.getElementById('chat-header-phone');
  const groupBadge = document.getElementById('chat-group-badge');
  const participantsContainer = document.getElementById('chat-group-participants');
  const broadcastNotice = document.getElementById('chat-group-broadcast-notice');
  const broadcastText = document.getElementById('chat-group-broadcast-text');
  const container = document.getElementById('chat-messages-container');

  const {
    smsThreads = {},
    googleContactsCache = [],
    deviceContactsCache = {}
  } = await chrome.storage.local.get(['smsThreads', 'googleContactsCache', 'deviceContactsCache']);

  const deviceList = Array.isArray(deviceContactsCache)
    ? deviceContactsCache
    : Object.values(deviceContactsCache);

  let threadsList = Object.values(smsThreads);
  if (threadsList.length === 0) threadsList = getFallbackSmsThreads();

  const thread = threadsList.find(t => String(t.threadId) === String(activeThreadId));
  if (!thread) return;

  const isGroup = !!thread.isGroup || (thread.recipients && thread.recipients.length > 1);
  const recips = thread.recipients || [];

  if (isGroup) {
    headerAvatar.textContent = '👥';
    headerAvatar.className = 'w-8 h-8 rounded-full bg-gradient-to-tr from-purple-600 to-indigo-600 border border-purple-500/40 flex items-center justify-center font-bold text-xs text-white shrink-0 shadow-sm';

    let title = thread.groupTitle;
    if (!title && recips.length > 0) {
      title = recips.map(r => resolveContactIdentity(r.phone || r.cleanDigits || r, googleContactsCache, deviceList).name).join(', ');
    }
    headerName.textContent = title || 'Group Conversation';
    headerPhone.textContent = `Group Chat • ${recips.length || 2} participants`;
    groupBadge.classList.remove('hidden');

    // Render Participant Chips
    participantsContainer.innerHTML = '';
    if (recips.length > 0) {
      recips.forEach(r => {
        const iden = resolveContactIdentity(r.phone || r.cleanDigits || r, googleContactsCache, deviceList);
        const chip = document.createElement('span');
        chip.className = 'inline-flex items-center gap-1 bg-slate-800 border border-slate-700 text-slate-300 text-[10px] px-2 py-0.5 rounded-full';
        chip.innerHTML = `<span class="text-purple-400 font-bold">•</span> ${escapeHtml(iden.name)}`;
        participantsContainer.appendChild(chip);
      });
      participantsContainer.classList.remove('hidden');
    } else {
      participantsContainer.classList.add('hidden');
    }

    broadcastNotice.classList.remove('hidden');
    broadcastText.textContent = `Group Conversation: Outbound message will broadcast to all ${recips.length || 'group'} participants.`;
  } else {
    // 1-on-1 Contact Identity Resolution
    const identity = resolveContactIdentity(thread.address, googleContactsCache, deviceList);
    const displayName = (thread.contactName && !thread.contactName.match(/^[+\d\s()-]+$/))
      ? thread.contactName
      : identity.name;

    if (identity.avatarUrl) {
      headerAvatar.innerHTML = `<img src="${identity.avatarUrl}" class="w-full h-full rounded-full object-cover">`;
    } else {
      headerAvatar.textContent = identity.initials;
    }
    headerAvatar.className = 'w-8 h-8 rounded-full bg-slate-800 border border-slate-700 flex items-center justify-center font-bold text-xs text-sky-400 shrink-0';

    headerName.textContent = displayName;
    headerPhone.textContent = formatPhoneNumber(thread.address);
    groupBadge.classList.add('hidden');
    participantsContainer.classList.add('hidden');
    broadcastNotice.classList.add('hidden');
  }

  // Extract messages and sort chronologically (oldest to newest)
  const rawMessages = thread.messages ? Object.values(thread.messages) : [];
  rawMessages.sort((a, b) => a.timestamp - b.timestamp);

  container.innerHTML = '';
  if (rawMessages.length === 0) {
    container.innerHTML = `
      <div class="h-full flex items-center justify-center text-xs text-slate-500">
        No message history recorded in this thread yet.
      </div>
    `;
    return;
  }

  let lastDateStr = '';
  rawMessages.forEach(msg => {
    const msgDate = new Date(msg.timestamp).toLocaleDateString([], { month: 'short', day: 'numeric' });
    if (msgDate !== lastDateStr) {
      const divider = document.createElement('div');
      divider.className = 'flex items-center justify-center my-2';
      divider.innerHTML = `<span class="bg-slate-900 border border-slate-800 text-[10px] font-semibold text-slate-500 px-2 py-0.5 rounded-full">${msgDate}</span>`;
      container.appendChild(divider);
      lastDateStr = msgDate;
    }

    // Resolve sender identity for message bubble
    let senderName = msg.contactName;
    if (!senderName || senderName.match(/^[+\d\s()-]+$/)) {
      const senderIden = resolveContactIdentity(msg.address, googleContactsCache, deviceList);
      senderName = senderIden.name;
    }

    const bubble = createChatBubble(msg, senderName, isGroup);
    container.appendChild(bubble);
  });

  // Auto-scroll to bottom of conversation
  container.scrollTop = container.scrollHeight;
}

/**
 * Requirements 3, 4, 5: Renders message bubble with inline photo/media thumbnail previews,
 * click-to-expand lightbox modal, rich URL OpenGraph previews, and real-time keyword highlighting.
 */
function createChatBubble(msg, senderName, isGroupChat = false) {
  const row = document.createElement('div');
  const isIncoming = msg.isIncoming;
  const timeStr = formatTimestamp(msg.timestamp);

  row.className = `flex flex-col ${isIncoming ? 'items-start' : 'items-end'} space-y-1`;

  const bubbleClass = isIncoming
    ? 'bg-slate-800 text-slate-100 rounded-2xl rounded-tl-sm border border-slate-700/60'
    : 'bg-sky-600 text-white rounded-2xl rounded-tr-sm shadow-md';

  // Format message text with clickable links and real-time search keyword highlighting
  let formattedBody = linkifyText(msg.body || '');
  if (currentSearchQuery) {
    formattedBody = highlightKeywords(formattedBody, currentSearchQuery);
  }

  // Detect photo / media attachment (MMS image parts, Base64 data URIs, or object URLs)
  const mediaSrc = msg.attachmentData || msg.attachmentUrl || (msg.attachment && (msg.attachment.base64 || msg.attachment.data));
  let mediaHtml = '';
  if (mediaSrc) {
    mediaHtml = `
      <div class="chat-media-thumbnail mt-2 overflow-hidden rounded-xl border border-slate-700/80 max-w-[280px] cursor-pointer group relative bg-black/40 shadow-sm" title="Click to view full photo in lightbox">
        <img src="${mediaSrc}" class="w-full max-h-[220px] object-cover group-hover:scale-105 transition-transform duration-200 rounded-xl select-none" alt="MMS Photo" loading="lazy">
        <div class="absolute inset-0 bg-black/30 opacity-0 group-hover:opacity-100 transition-opacity flex items-center justify-center rounded-xl">
          <span class="bg-slate-900/90 text-white text-[10px] px-2 py-1 rounded-md font-medium border border-slate-700 shadow-md">🔍 Click to expand</span>
        </div>
      </div>
    `;
  }

  // Detect URL for OpenGraph Link Preview Card
  const urlMatches = (msg.body || '').match(/https?:\/\/[^\s<>"']+/gi);
  const detectedUrl = urlMatches ? urlMatches[0] : null;
  const previewContainerId = detectedUrl ? `preview-${Math.random().toString(36).substring(2, 9)}` : null;
  const linkPreviewHtml = detectedUrl ? `<div id="${previewContainerId}" class="link-preview-slot mt-2"></div>` : '';

  row.innerHTML = `
    <div class="max-w-[82%] px-3.5 py-2 text-xs leading-relaxed break-words ${bubbleClass}">
      ${isIncoming && isGroupChat && senderName ? `
        <p class="text-[10px] font-bold text-sky-400 mb-0.5 flex items-center gap-1">
          <span class="w-1.5 h-1.5 rounded-full bg-sky-400"></span>
          ${escapeHtml(senderName)}
        </p>
      ` : ''}
      ${formattedBody ? `<div class="select-text whitespace-pre-wrap">${formattedBody}</div>` : ''}
      ${mediaHtml}
      ${linkPreviewHtml}
    </div>
    <div class="flex items-center gap-1 text-[10px] text-slate-500 px-1">
      <span>${timeStr}</span>
      ${!isIncoming ? `<span class="text-sky-400">✓✓</span>` : ''}
    </div>
  `;

  // Attach media thumbnail click listener to open full lightbox
  if (mediaSrc) {
    const thumb = row.querySelector('.chat-media-thumbnail');
    thumb?.addEventListener('click', () => {
      openMediaLightbox(mediaSrc, msg.body || senderName || 'MMS Photo Attachment');
    });
  }

  // Asynchronously fetch OpenGraph preview and render thumbnail card
  if (detectedUrl && previewContainerId) {
    renderUrlPreviewCard(detectedUrl, previewContainerId);
  }

  return row;
}

/**
 * Requirement 5: Asynchronously fetches OpenGraph preview metadata
 * and displays rich card thumbnail preview inside chat bubble.
 */
async function renderUrlPreviewCard(url, containerId) {
  try {
    const res = await chrome.runtime.sendMessage({
      type: 'FETCH_LINK_PREVIEW',
      url
    });

    const slot = document.getElementById(containerId);
    if (!slot || !res || !res.success || !res.preview) return;

    const preview = res.preview;
    const cardLink = document.createElement('a');
    cardLink.href = preview.url;
    cardLink.target = '_blank';
    cardLink.rel = 'noopener noreferrer';
    cardLink.className = 'block rounded-xl border border-slate-700/60 bg-slate-900/90 hover:bg-slate-800/90 transition overflow-hidden text-left shadow-sm';

    if (preview.image) {
      const img = document.createElement('img');
      img.src = preview.image;
      img.className = 'w-full h-28 object-cover border-b border-slate-800';
      img.alt = preview.title || '';
      img.addEventListener('error', () => img.remove());
      cardLink.appendChild(img);
    }

    const contentDiv = document.createElement('div');
    contentDiv.className = 'p-2 space-y-0.5';
    contentDiv.innerHTML = `
      <div class="flex items-center gap-1.5 text-[10px] text-slate-400 font-mono">
        <span>🔗</span>
        <span class="truncate">${escapeHtml(preview.domain || preview.siteName || '')}</span>
      </div>
      <p class="text-[11px] font-bold text-slate-100 line-clamp-1 leading-snug">${escapeHtml(preview.title || preview.domain || '')}</p>
      ${preview.description ? `<p class="text-[10px] text-slate-400 line-clamp-2 leading-relaxed">${escapeHtml(preview.description)}</p>` : ''}
    `;
    cardLink.appendChild(contentDiv);

    slot.innerHTML = '';
    slot.appendChild(cardLink);
  } catch (e) {
    // Graceful fallback: silently leave just the clickable text link
  }
}

/**
 * Requirement 4: Fullscreen Photo / Media Lightbox Modal handler
 */
function setupMediaLightbox() {
  if (mediaLightboxInitialized) return;
  mediaLightboxInitialized = true;

  const modal = document.getElementById('media-lightbox-modal');
  const btnClose = document.getElementById('btn-close-lightbox');

  btnClose?.addEventListener('click', closeMediaLightbox);

  modal?.addEventListener('click', (e) => {
    if (e.target === modal) {
      closeMediaLightbox();
    }
  });

  document.addEventListener('keydown', (e) => {
    if (e.key === 'Escape' && modal && !modal.classList.contains('hidden')) {
      closeMediaLightbox();
    }
  });
}

function openMediaLightbox(src, caption = '') {
  const modal = document.getElementById('media-lightbox-modal');
  const img = document.getElementById('lightbox-image');
  const captionEl = document.getElementById('lightbox-caption');
  const downloadLink = document.getElementById('lightbox-download-link');

  if (!modal || !img) return;

  img.src = src;
  if (captionEl) captionEl.textContent = caption;
  if (downloadLink) {
    downloadLink.href = src;
    downloadLink.download = `PushBridge-photo-${Date.now()}.jpg`;
  }

  modal.classList.remove('hidden');
}

function closeMediaLightbox() {
  const modal = document.getElementById('media-lightbox-modal');
  const img = document.getElementById('lightbox-image');
  if (modal) modal.classList.add('hidden');
  if (img) img.src = '';
}

/**
 * Requirement 3: Real-time search keyword highlighting helper
 */
function highlightKeywords(htmlText, query) {
  if (!query || !query.trim() || !htmlText) return htmlText;
  const terms = query.trim().split(/\s+/).filter(Boolean);
  if (terms.length === 0) return htmlText;

  // Split text by HTML tags to only highlight text outside tags
  const parts = htmlText.split(/(<[^>]+>)/g);
  const regex = new RegExp(`(${terms.map(t => t.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')).join('|')})`, 'gi');

  return parts.map(part => {
    if (part.startsWith('<') && part.endsWith('>')) {
      return part; // inside an HTML tag, don't modify
    }
    return part.replace(regex, '<mark class="bg-amber-400/30 text-amber-200 px-0.5 rounded font-medium">$1</mark>');
  }).join('');
}

/**
 * Requirement 5: Converts web URLs into secure clickable links
 */
function linkifyText(text) {
  if (!text) return '';
  const escaped = escapeHtml(text);
  const urlPattern = /(https?:\/\/[^\s<>"']+)/gi;
  return escaped.replace(urlPattern, '<a href="$1" target="_blank" rel="noopener noreferrer" class="text-sky-300 underline hover:text-sky-100 font-medium break-all">$1</a>');
}

// =============================================================
// 7. Clipboard Sync
// =============================================================
function setupClipboardSync() {
  const phoneClipText = document.getElementById('phone-clip-text');
  const btnCopyToDesktop = document.getElementById('btn-copy-to-desktop');
  const btnReadDesktopClip = document.getElementById('btn-read-desktop-clip');
  const desktopClipInput = document.getElementById('desktop-clip-input');
  const btnPushClip = document.getElementById('btn-push-clip');

  btnCopyToDesktop?.addEventListener('click', async () => {
    const text = phoneClipText.getAttribute('data-raw-text') || phoneClipText.textContent;
    if (!text || text.includes('Waiting for text')) {
      showToast('No phone clipboard text available to copy.');
      return;
    }
    await navigator.clipboard.writeText(text);
    showToast('✓ Copied phone snippet to desktop clipboard!');
  });

  btnReadDesktopClip?.addEventListener('click', async () => {
    try {
      const text = await navigator.clipboard.readText();
      desktopClipInput.value = text;
      showToast('✓ Read desktop clipboard into input.');
    } catch (e) {
      showToast(`Permission required: ${e.message}`);
    }
  });

  btnPushClip?.addEventListener('click', async () => {
    const text = desktopClipInput.value.trim();
    if (!text) {
      showToast('Please type or paste text to push.');
      return;
    }

    try {
      const res = await chrome.runtime.sendMessage({
        type: 'SYNC_CLIPBOARD_TO_DEVICE',
        targetDeviceId: currentTargetDeviceId || 'android_companion',
        text
      });
      if (res && res.success) {
        showToast('✓ Pushed to phone clipboard via Firebase!');
        desktopClipInput.value = '';
      }
    } catch (err) {
      showToast(`Sync failed: ${err.message}`);
    }
  });
}

async function loadClipboardState() {
  const phoneClipText = document.getElementById('phone-clip-text');
  const phoneClipTime = document.getElementById('phone-clip-time');
  const { syncedClipboard } = await chrome.storage.local.get('syncedClipboard');

  if (syncedClipboard && syncedClipboard.text) {
    phoneClipText.textContent = syncedClipboard.text;
    phoneClipText.setAttribute('data-raw-text', syncedClipboard.text);
    phoneClipTime.textContent = formatTimestamp(syncedClipboard.timestamp);
  }
}

// =============================================================
// 8. Global Listeners & Utilities
// =============================================================
function setupGlobalEvents() {
  document.getElementById('btn-test-toast')?.addEventListener('click', async () => {
    await chrome.runtime.sendMessage({ type: 'TEST_NOTIFICATION_ALERT' });
    await loadNotifications();
  });

  // Check status button on offline banner
  document.getElementById('btn-reconnect-check')?.addEventListener('click', async () => {
    await checkStreamStatus();
    await checkDevicePresence();
    showToast('Checking device presence status...');
  });

  // Listen for real-time background broadcasts
  chrome.runtime.onMessage.addListener(async (message) => {
    if (message.type === 'NOTIFICATION_RECEIVED') {
      loadNotifications();
    } else if (message.type === 'SMS_THREADS_UPDATED') {
      loadSmsThreads();
    } else if (message.type === 'DEVICE_CONTACTS_UPDATED') {
      loadSmsThreads();
    } else if (message.type === 'DEVICE_PRESENCE_CHANGED') {
      if (!currentTargetDeviceId || message.deviceId === currentTargetDeviceId) {
        updatePresenceUI(message.presence);
      }
    } else if (message.type === 'CLIPBOARD_UPDATED') {
      loadClipboardState();
    }
  });
}

function showToast(msg) {
  const toast = document.getElementById('global-toast');
  if (!toast) return;
  toast.textContent = msg;
  toast.classList.remove('hidden');
  setTimeout(() => toast.classList.add('hidden'), 3500);
}

function formatTimestamp(ts) {
  if (!ts) return '';
  const diff = Date.now() - ts;
  if (diff < 60000) return 'Just now';
  if (diff < 3600000) return `${Math.floor(diff / 60000)}m ago`;
  const d = new Date(ts);
  return d.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
}

function formatBytes(bytes) {
  if (!bytes) return '0 B';
  const k = 1024;
  const sizes = ['B', 'KB', 'MB', 'GB'];
  const i = Math.floor(Math.log(bytes) / Math.log(k));
  return parseFloat((bytes / Math.pow(k, i)).toFixed(1)) + ' ' + sizes[i];
}

function escapeHtml(str) {
  if (!str) return '';
  return String(str)
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#039;');
}

/**
 * Robust demo threads including 1-on-1 and multi-recipient group conversations
 */
function getFallbackSmsThreads() {
  return [
    {
      threadId: 201,
      address: '+1 (555) 234-5678, +1 (555) 876-5432, +1 (555) 901-2345',
      contactName: 'Weekend Hike & BBQ',
      groupTitle: 'Weekend Hike & BBQ',
      isGroup: true,
      recipients: [
        { phone: '+1 (555) 234-5678', name: 'Alice Cooper', cleanDigits: '5552345678' },
        { phone: '+1 (555) 876-5432', name: 'Bob Martinez', cleanDigits: '5558765432' },
        { phone: '+1 (555) 901-2345', name: 'Sarah Connor', cleanDigits: '5559012345' }
      ],
      snippet: 'Sarah: Sounds great, count me in for the trailhead at 9 AM!',
      lastTimestamp: Date.now() - 1000 * 60 * 5,
      unreadCount: 2,
      messages: {
        g1: {
          id: 'g1',
          threadId: 201,
          address: '+1 (555) 234-5678',
          contactName: 'Alice Cooper',
          body: 'Hey everyone! Are we still planning on meeting at Bear Peak this Saturday?',
          timestamp: Date.now() - 1000 * 60 * 30,
          isIncoming: true,
          isRead: true
        },
        g2: {
          id: 'g2',
          threadId: 201,
          address: '+1 (555) 876-5432',
          contactName: 'Bob Martinez',
          body: 'Yes! Weather looks clear and sunny. Check the trail map here: https://www.alltrails.com',
          timestamp: Date.now() - 1000 * 60 * 18,
          isIncoming: true,
          isRead: true
        },
        g3: {
          id: 'g3',
          threadId: 201,
          address: 'chrome_desktop',
          contactName: 'Me',
          body: 'Here is the parking permit pass for the trailhead:',
          attachmentData: 'data:image/svg+xml;utf8,<svg xmlns="http://www.w3.org/2000/svg" width="400" height="240" viewBox="0 0 400 240"><rect width="400" height="240" rx="16" fill="%231e293b"/><rect x="20" y="20" width="360" height="200" rx="12" fill="%230f172a" stroke="%2338bdf8" stroke-width="2"/><text x="200" y="70" fill="%2338bdf8" font-size="20" font-weight="bold" font-family="sans-serif" text-anchor="middle">BEAR PEAK TRAIL PERMIT</text><text x="200" y="115" fill="%23f8fafc" font-size="14" font-family="sans-serif" text-anchor="middle">Weekend Day Pass • Group 4</text><circle cx="200" cy="155" r="24" fill="%230284c7"/><text x="200" y="162" fill="%23ffffff" font-size="20" font-weight="bold" text-anchor="middle">✓</text><text x="200" y="200" fill="%2394a3b8" font-size="11" font-family="monospace" text-anchor="middle">ID: BP-2026-99482</text></svg>',
          attachmentMime: 'image/svg+xml',
          timestamp: Date.now() - 1000 * 60 * 10,
          isIncoming: false,
          isRead: true
        },
        g4: {
          id: 'g4',
          threadId: 201,
          address: '+1 (555) 901-2345',
          contactName: 'Sarah Connor',
          body: 'Sounds great, count me in for the trailhead at 9 AM!',
          timestamp: Date.now() - 1000 * 60 * 5,
          isIncoming: true,
          isRead: false
        }
      }
    },
    {
      threadId: 101,
      address: '+1 (555) 234-5678',
      contactName: 'Alice Cooper',
      snippet: 'Are we still on for lunch today?',
      lastTimestamp: Date.now() - 1000 * 60 * 12,
      unreadCount: 1,
      isGroup: false,
      recipients: [],
      messages: {
        m1: {
          id: 'm1',
          threadId: 101,
          address: '+1 (555) 234-5678',
          body: 'Hey! Did you get the documents from the bridge app?',
          timestamp: Date.now() - 1000 * 60 * 25,
          isIncoming: true,
          isRead: true
        },
        m2: {
          id: 'm2',
          threadId: 101,
          address: '+1 (555) 234-5678',
          body: 'Yes, reviewing them right now on Chrome.',
          timestamp: Date.now() - 1000 * 60 * 18,
          isIncoming: false,
          isRead: true
        },
        m3: {
          id: 'm3',
          threadId: 101,
          address: '+1 (555) 234-5678',
          body: 'Awesome! Are we still on for lunch today?',
          timestamp: Date.now() - 1000 * 60 * 12,
          isIncoming: true,
          isRead: false
        }
      }
    },
    {
      threadId: 102,
      address: '+1 (555) 876-5432',
      contactName: 'Bob Martinez',
      snippet: 'The server deployment finished cleanly.',
      lastTimestamp: Date.now() - 1000 * 60 * 90,
      unreadCount: 0,
      isGroup: false,
      recipients: [],
      messages: {
        m4: {
          id: 'm4',
          threadId: 102,
          address: '+1 (555) 876-5432',
          body: 'Running the migration now.',
          timestamp: Date.now() - 1000 * 60 * 110,
          isIncoming: true,
          isRead: true
        },
        m5: {
          id: 'm5',
          threadId: 102,
          address: '+1 (555) 876-5432',
          body: 'The server deployment finished cleanly.',
          timestamp: Date.now() - 1000 * 60 * 90,
          isIncoming: true,
          isRead: true
        }
      }
    }
  ];
}
