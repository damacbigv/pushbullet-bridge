/**
 * PushBridge v2 Popup & Desktop Dashboard Controller
 * Implements Threaded SMS Chat, Google Contacts Integration (People API),
 * and Flexible Notification Bundling & Sorting Views.
 */
import { syncGoogleContacts, searchContacts } from '../contacts/google-contacts.js';

// Application State
let currentViewMode = 'bundled'; // 'bundled' | 'chronological'
let currentTargetDeviceId = '';
let activeThreadId = null;
let newChatRecipient = '';
let selectedChatAttachment = null;
let collapsedBundles = new Set();
let autocompleteDebounceTimer = null;

document.addEventListener('DOMContentLoaded', async () => {
  setupTabs();
  setupDeviceSelector();
  setupGoogleContacts();
  setupNotificationViews();
  setupThreadedSms();
  setupClipboardSync();
  setupWindowExpansion();
  setupGlobalEvents();

  await checkStreamStatus();
  await restoreSavedState();
  await loadNotifications();
  await loadSmsThreads();
  await loadClipboardState();
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
  const saved = await chrome.storage.local.get(['notificationViewMode', 'activeTargetDeviceId']);
  if (saved.notificationViewMode) {
    currentViewMode = saved.notificationViewMode;
  }
  updateViewModeToggleUI();
}

function setupWindowExpansion() {
  document.getElementById('btn-expand-tab')?.addEventListener('click', () => {
    chrome.tabs.create({ url: chrome.runtime.getURL('popup/popup.html') });
  });
}

// =============================================================
// 2. Stream & Device Selector
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
      devices.forEach(devId => {
        const opt = document.createElement('option');
        opt.value = devId;
        opt.textContent = devId.startsWith('android') ? `📱 ${devId}` : `💻 ${devId}`;
        if (devId === activeTargetDeviceId) {
          opt.selected = true;
          currentTargetDeviceId = devId;
        }
        select.appendChild(opt);
      });
      if (!currentTargetDeviceId && devices.length > 0) {
        currentTargetDeviceId = devices[0];
      }
    }
  } catch {
    select.innerHTML = '<option value="android_companion">Android Companion</option>';
    currentTargetDeviceId = 'android_companion';
  }

  select.addEventListener('change', async (e) => {
    currentTargetDeviceId = e.target.value;
    await chrome.storage.local.set({ activeTargetDeviceId: currentTargetDeviceId });
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

  chrome.storage.local.get(['googleContactsCache', 'googleContactsLastSync']).then(({ googleContactsCache = [] }) => {
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
      showToast(`Google Contacts connected (Local Cache: ${result.contacts?.length || 0})`);
    }
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

/**
 * Mode A: Group notifications hierarchically by app source package name
 */
function renderBundledNotifications(container, notifications) {
  // Group by app name / package name
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

    // Collapsible Header
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

    // Toggle collapse
    header.addEventListener('click', (e) => {
      if (e.target.closest('.btn-dismiss-bundle')) return;
      if (collapsedBundles.has(appName)) {
        collapsedBundles.delete(appName);
      } else {
        collapsedBundles.add(appName);
      }
      loadNotifications();
    });

    // Dismiss All from this App bundle
    header.querySelector('.btn-dismiss-bundle')?.addEventListener('click', async (e) => {
      e.stopPropagation();
      await dismissGroup(group.items.map(i => i.id));
    });

    bundleCard.appendChild(header);

    // Items list
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

/**
 * Mode B: Flat timeline sorted strictly by date/time received descending
 */
function renderChronologicalNotifications(container, notifications) {
  // Sort strictly chronological descending
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

  // Dismiss action
  card.querySelector('.btn-dismiss-item')?.addEventListener('click', async () => {
    await dismissNotification(item.id);
  });

  // Reply submission
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
// 5. Threaded SMS Chat Interface & Autocomplete
// =============================================================
function setupThreadedSms() {
  const searchInput = document.getElementById('sms-search-input');
  const popover = document.getElementById('contacts-autocomplete-popover');
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

  // Autocomplete search typing
  searchInput?.addEventListener('input', () => {
    clearTimeout(autocompleteDebounceTimer);
    autocompleteDebounceTimer = setTimeout(async () => {
      const q = searchInput.value.trim();
      if (!q) {
        popover.classList.add('hidden');
        await loadSmsThreads();
        return;
      }
      const matches = await searchContacts(q);
      renderAutocompletePopover(popover, matches);
    }, 150);
  });

  // Close autocomplete on click outside
  document.addEventListener('click', (e) => {
    if (!popover.contains(e.target) && e.target !== searchInput) {
      popover.classList.add('hidden');
    }
  });

  // New Chat action
  btnNewChat?.addEventListener('click', () => {
    startNewConversation();
  });

  // Manual SMS Sync from phone
  btnManualSync?.addEventListener('click', triggerSmsSync);
  btnSyncThreads?.addEventListener('click', triggerSmsSync);

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

  // Submit chat message
  composerForm?.addEventListener('submit', async (e) => {
    e.preventDefault();
    const text = composerInput.value.trim();
    if (!text && !selectedChatAttachment) return;

    let targetRecipient = '';
    let targetThreadId = activeThreadId;

    if (activeThreadId === 'new_chat') {
      targetRecipient = newRecipientInput.value.trim();
      if (!targetRecipient) {
        showToast('Please enter a recipient phone number.');
        newRecipientInput.focus();
        return;
      }
    } else {
      const { smsThreads = {} } = await chrome.storage.local.get('smsThreads');
      const thread = smsThreads[String(activeThreadId)];
      if (!thread) {
        showToast('No active conversation selected.');
        return;
      }
      targetRecipient = thread.address;
    }

    composerInput.value = '';
    selectedChatAttachment = null;
    fileInput.value = '';
    attachmentPreview.classList.add('hidden');

    try {
      const res = await chrome.runtime.sendMessage({
        type: 'SEND_CHAT_MESSAGE',
        targetDeviceId: currentTargetDeviceId || 'android_companion',
        threadId: targetThreadId !== 'new_chat' ? targetThreadId : 0,
        recipient: targetRecipient,
        messageBody: text,
        attachment: selectedChatAttachment
      });

      if (res && res.success) {
        if (activeThreadId === 'new_chat' && res.threadKey) {
          activeThreadId = res.threadKey;
          document.getElementById('new-recipient-bar').classList.add('hidden');
        }
        await loadSmsThreads();
        renderActiveConversation();
      } else {
        showToast('Failed to dispatch message to phone.');
      }
    } catch (err) {
      showToast(`Error sending message: ${err.message}`);
    }
  });
}

async function triggerSmsSync() {
  showToast('Requesting SMS message threads from Android phone...');
  try {
    await chrome.runtime.sendMessage({
      type: 'TRIGGER_SMS_SYNC',
      targetDeviceId: currentTargetDeviceId || 'android_companion'
    });
    setTimeout(async () => {
      await loadSmsThreads();
      showToast('✓ SMS threads refreshed from phone');
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
  const recipientBar = document.getElementById('new-recipient-bar');
  const recipientInput = document.getElementById('new-recipient-input');
  const container = document.getElementById('chat-messages-container');

  headerAvatar.textContent = '+';
  headerName.textContent = recipientName || 'New Conversation';
  headerPhone.textContent = recipientPhone || 'Select recipient below';

  recipientBar.classList.remove('hidden');
  recipientInput.value = recipientPhone;
  recipientInput.focus();

  container.innerHTML = `
    <div class="h-full flex flex-col items-center justify-center text-center p-6 text-slate-500">
      <div class="w-12 h-12 rounded-full bg-slate-900 border border-slate-800 flex items-center justify-center text-xl mb-2 text-slate-400">
        ✉️
      </div>
      <p class="text-xs font-semibold text-slate-300">Start New Message</p>
      <p class="text-[11px] text-slate-500 mt-1 max-w-xs">Type a phone number or select a Google Contact to begin.</p>
    </div>
  `;

  // Deselect sidebar items
  document.querySelectorAll('.thread-item-card').forEach(el => el.classList.remove('bg-slate-800', 'border-sky-500'));
}

async function loadSmsThreads() {
  const container = document.getElementById('thread-list-container');
  const counter = document.getElementById('threads-counter');
  const summaryText = document.getElementById('threads-summary-text');

  let { smsThreads = {} } = await chrome.storage.local.get('smsThreads');
  let threadsList = Object.values(smsThreads);

  if (threadsList.length === 0) {
    // Attempt fetching from Firebase RTDB
    try {
      const res = await chrome.runtime.sendMessage({
        type: 'FETCH_SMS_THREADS',
        targetDeviceId: currentTargetDeviceId || 'android_companion'
      });
      if (res && res.threads) {
        smsThreads = res.threads;
        threadsList = Object.values(smsThreads);
      }
    } catch {}
  }

  // Fallback demo conversations if empty so interface displays immediately
  if (threadsList.length === 0) {
    threadsList = getFallbackSmsThreads();
  }

  // Sort newest first
  threadsList.sort((a, b) => b.lastTimestamp - a.lastTimestamp);

  counter.textContent = threadsList.length;
  summaryText.textContent = `${threadsList.length} conversation${threadsList.length === 1 ? '' : 's'}`;

  container.innerHTML = '';
  threadsList.forEach(thread => {
    const card = createThreadListItem(thread);
    container.appendChild(card);
  });

  // Select first thread by default if none selected
  if (!activeThreadId && threadsList.length > 0) {
    activeThreadId = String(threadsList[0].threadId);
  }

  renderActiveConversation();
}

function createThreadListItem(thread) {
  const item = document.createElement('div');
  const isSelected = String(thread.threadId) === String(activeThreadId);
  item.className = `thread-item-card p-3 flex items-center gap-2.5 cursor-pointer transition border-l-2 select-none hover:bg-slate-800/60 ${
    isSelected ? 'bg-slate-800 border-sky-500' : 'border-transparent'
  }`;

  const displayName = thread.contactName || thread.address || 'Unknown';
  const initials = getInitials(displayName);
  const timeStr = formatTimestamp(thread.lastTimestamp);

  item.innerHTML = `
    <!-- Contact Avatar -->
    <div class="w-9 h-9 rounded-full bg-gradient-to-tr from-sky-600 to-indigo-600 flex items-center justify-center text-white text-xs font-bold shrink-0 shadow-sm">
      ${initials}
    </div>

    <!-- Info & Snippet -->
    <div class="flex-1 min-w-0">
      <div class="flex items-center justify-between mb-0.5">
        <h3 class="text-xs font-bold text-slate-100 truncate">${escapeHtml(displayName)}</h3>
        <span class="text-[10px] text-slate-500 shrink-0">${timeStr}</span>
      </div>
      <p class="text-[11px] text-slate-400 truncate">${escapeHtml(thread.snippet || 'No messages')}</p>
    </div>

    ${thread.unreadCount > 0 ? `
      <span class="bg-sky-500 text-white text-[10px] font-bold px-1.5 py-0.2 rounded-full shrink-0">
        ${thread.unreadCount}
      </span>
    ` : ''}
  `;

  item.addEventListener('click', () => {
    activeThreadId = String(thread.threadId);
    document.getElementById('new-recipient-bar').classList.add('hidden');
    document.querySelectorAll('.thread-item-card').forEach(el => el.classList.remove('bg-slate-800', 'border-sky-500'));
    item.classList.add('bg-slate-800', 'border-sky-500');
    renderActiveConversation();
  });

  return item;
}

async function renderActiveConversation() {
  if (activeThreadId === 'new_chat') return;

  const headerAvatar = document.getElementById('chat-header-avatar');
  const headerName = document.getElementById('chat-header-name');
  const headerPhone = document.getElementById('chat-header-phone');
  const container = document.getElementById('chat-messages-container');

  const { smsThreads = {} } = await chrome.storage.local.get('smsThreads');
  let threadsList = Object.values(smsThreads);
  if (threadsList.length === 0) threadsList = getFallbackSmsThreads();

  const thread = threadsList.find(t => String(t.threadId) === String(activeThreadId));
  if (!thread) return;

  const displayName = thread.contactName || thread.address || 'Unknown';
  headerAvatar.textContent = getInitials(displayName);
  headerName.textContent = displayName;
  headerPhone.textContent = thread.address || '';

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

    const bubble = createChatBubble(msg, displayName);
    container.appendChild(bubble);
  });

  // Auto-scroll to bottom of conversation
  container.scrollTop = container.scrollHeight;
}

function createChatBubble(msg, contactName) {
  const row = document.createElement('div');
  const isIncoming = msg.isIncoming;
  const timeStr = formatTimestamp(msg.timestamp);

  row.className = `flex flex-col ${isIncoming ? 'items-start' : 'items-end'} space-y-1`;

  const bubbleClass = isIncoming
    ? 'bg-slate-800 text-slate-100 rounded-2xl rounded-tl-sm border border-slate-700/60'
    : 'bg-sky-600 text-white rounded-2xl rounded-tr-sm shadow-md';

  row.innerHTML = `
    <div class="max-w-[78%] px-3.5 py-2 text-xs leading-relaxed break-words ${bubbleClass}">
      ${isIncoming && contactName ? `<p class="text-[10px] font-bold text-sky-400 mb-0.5">${escapeHtml(contactName)}</p>` : ''}
      <p class="select-text whitespace-pre-wrap">${escapeHtml(msg.body)}</p>
    </div>
    <div class="flex items-center gap-1 text-[10px] text-slate-500 px-1">
      <span>${timeStr}</span>
      ${!isIncoming ? `<span class="text-sky-400">✓✓</span>` : ''}
    </div>
  `;

  return row;
}

function renderAutocompletePopover(popover, matches) {
  if (matches.length === 0) {
    popover.innerHTML = '<div class="p-3 text-xs text-slate-500 text-center">No matching Google Contacts found.</div>';
    popover.classList.remove('hidden');
    return;
  }

  popover.innerHTML = '';
  matches.forEach(contact => {
    const item = document.createElement('div');
    item.className = 'p-2.5 flex items-center gap-2.5 hover:bg-slate-800 cursor-pointer transition select-none';

    item.innerHTML = `
      <div class="w-8 h-8 rounded-full bg-slate-700 flex items-center justify-center font-bold text-xs text-sky-300 shrink-0">
        ${contact.photoUrl ? `<img src="${contact.photoUrl}" class="w-full h-full rounded-full object-cover">` : contact.initials}
      </div>
      <div class="flex-1 min-w-0">
        <p class="text-xs font-bold text-slate-100 truncate">${escapeHtml(contact.name)}</p>
        <p class="text-[11px] text-slate-400 font-mono truncate">${escapeHtml(contact.phone)} (${contact.type})</p>
      </div>
    `;

    item.addEventListener('click', async () => {
      popover.classList.add('hidden');
      document.getElementById('sms-search-input').value = '';

      // Check if thread already exists for this phone
      const { smsThreads = {} } = await chrome.storage.local.get('smsThreads');
      const cleanTarget = contact.cleanDigits;
      const existingThread = Object.values(smsThreads).find(t =>
        (t.address && t.address.replace(/\D/g, '').includes(cleanTarget)) ||
        (cleanTarget.includes(t.address.replace(/\D/g, '')))
      );

      if (existingThread) {
        activeThreadId = String(existingThread.threadId);
        document.getElementById('new-recipient-bar').classList.add('hidden');
        await loadSmsThreads();
      } else {
        startNewConversation(contact.phone, contact.name);
      }
    });

    popover.appendChild(item);
  });

  popover.classList.remove('hidden');
}

// =============================================================
// 6. Clipboard Sync
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
// 7. Global Listeners & Utilities
// =============================================================
function setupGlobalEvents() {
  // Test Toast Alert
  document.getElementById('btn-test-toast')?.addEventListener('click', async () => {
    await chrome.runtime.sendMessage({ type: 'TEST_NOTIFICATION_ALERT' });
    await loadNotifications();
  });

  // Listen for real-time background broadcasts
  chrome.runtime.onMessage.addListener((message) => {
    if (message.type === 'NOTIFICATION_RECEIVED') {
      loadNotifications();
    } else if (message.type === 'SMS_THREADS_UPDATED') {
      loadSmsThreads();
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

function getInitials(name) {
  if (!name) return '??';
  const parts = name.trim().split(/\s+/);
  if (parts.length === 1) return parts[0].substring(0, 2).toUpperCase();
  return (parts[0][0] + parts[parts.length - 1][0]).toUpperCase();
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

function getFallbackSmsThreads() {
  return [
    {
      threadId: 101,
      address: '+1 (555) 234-5678',
      contactName: 'Alice Cooper',
      snippet: 'Are we still on for lunch today?',
      lastTimestamp: Date.now() - 1000 * 60 * 12,
      unreadCount: 1,
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
