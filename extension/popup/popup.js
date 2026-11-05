/**
 * PushBridge Popup Controller
 * Manages Live Notification Feed, Inline Replies, SMS Composition with Base64 attachments,
 * and Universal Machine Clipboard Sync.
 */

let selectedAttachment = null;
let currentTargetDeviceId = '';

document.addEventListener('DOMContentLoaded', async () => {
  setupTabs();
  setupDeviceSelector();
  setupFeed();
  setupSmsComposer();
  setupClipboardSync();
  setupDiagnostics();
  await checkStreamStatus();
  await loadNotificationsFeed();
  await loadClipboardState();
});

// -------------------------------------------------------------
// 1. Navigation & Tabs
// -------------------------------------------------------------
function setupTabs() {
  const tabButtons = document.querySelectorAll('.tab-button');
  const tabPanes = document.querySelectorAll('.tab-pane');

  tabButtons.forEach(btn => {
    btn.addEventListener('click', () => {
      const targetTabId = btn.getAttribute('data-tab');

      // Update button styles
      tabButtons.forEach(b => {
        b.classList.remove('active', 'text-sky-400', 'border-sky-500', 'font-semibold');
        b.classList.add('text-slate-400', 'border-transparent', 'font-medium');
      });

      btn.classList.add('active', 'text-sky-400', 'border-sky-500', 'font-semibold');
      btn.classList.remove('text-slate-400', 'border-transparent', 'font-medium');

      // Show target tab pane
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

// -------------------------------------------------------------
// 2. Stream Status & Device Discovery
// -------------------------------------------------------------
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
  });
}

// -------------------------------------------------------------
// 3. Live Notification Feed & Inline Replies
// -------------------------------------------------------------
function setupFeed() {
  document.getElementById('btn-clear-feed')?.addEventListener('click', async () => {
    await chrome.storage.local.set({ notificationHistory: [] });
    await loadNotificationsFeed();
  });

  document.getElementById('btn-empty-test')?.addEventListener('click', async () => {
    await chrome.runtime.sendMessage({ type: 'TEST_NOTIFICATION_ALERT' });
    await loadNotificationsFeed();
  });

  // Listen for real-time notification arrivals from service worker
  chrome.runtime.onMessage.addListener((message) => {
    if (message.type === 'NOTIFICATION_RECEIVED') {
      loadNotificationsFeed();
    }
  });
}

async function loadNotificationsFeed() {
  const container = document.getElementById('notifications-container');
  const emptyState = document.getElementById('feed-empty-state');
  const counter = document.getElementById('feed-counter');

  const { notificationHistory = [] } = await chrome.storage.local.get('notificationHistory');
  counter.textContent = notificationHistory.length;

  if (notificationHistory.length === 0) {
    container.innerHTML = '';
    emptyState.classList.remove('hidden');
    return;
  }

  emptyState.classList.add('hidden');
  container.innerHTML = '';

  notificationHistory.forEach(item => {
    const card = createNotificationCard(item);
    container.appendChild(card);
  });
}

function createNotificationCard(item) {
  const card = document.createElement('div');
  card.className = 'rounded-xl border border-slate-800 bg-slate-900/80 p-3.5 space-y-2.5 transition hover:border-slate-700 shadow-sm';
  card.id = `card-${item.id}`;

  const timeStr = formatTimestamp(item.timestamp);
  const badgeColor = getAppBadgeStyle(item.appName, item.packageName);

  card.innerHTML = `
    <!-- Card Header -->
    <div class="flex items-center justify-between">
      <div class="flex items-center gap-2">
        <span class="text-[10px] font-semibold px-2 py-0.5 rounded-full border ${badgeColor}">
          ${escapeHtml(item.appName)}
        </span>
        ${item.subText ? `<span class="text-[10px] text-slate-500 font-medium truncate max-w-[140px]">${escapeHtml(item.subText)}</span>` : ''}
      </div>
      <div class="flex items-center gap-2">
        <span class="text-[10px] text-slate-500">${timeStr}</span>
        <button class="btn-dismiss-notif text-slate-500 hover:text-rose-400 p-0.5 text-xs transition cursor-pointer" title="Dismiss" data-id="${item.id}">✕</button>
      </div>
    </div>

    <!-- Content -->
    <div>
      <h3 class="text-xs font-bold text-slate-100">${escapeHtml(item.title)}</h3>
      <p class="text-xs text-slate-300 mt-0.5 leading-relaxed break-words">${escapeHtml(item.text)}</p>
    </div>

    <!-- Actions / Inline Reply Form -->
    <div class="pt-1">
      ${item.canReply ? `
        <form class="inline-reply-form flex items-center gap-2 mt-1" data-key="${escapeHtml(item.notificationKey)}" data-notif-id="${item.id}">
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

  // Attach dismiss handler
  card.querySelector('.btn-dismiss-notif')?.addEventListener('click', async (e) => {
    e.stopPropagation();
    await dismissNotification(item.id);
  });

  // Attach reply handler
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
          feedback.textContent = `✓ Reply sent natively to ${item.appName}`;
          feedback.className = 'reply-feedback text-[11px] font-medium text-emerald-400 mt-1.5';
          input.value = '';
          setTimeout(() => feedback.classList.add('hidden'), 3500);
        } else {
          feedback.textContent = `✗ Failed: ${response?.error || 'Could not dispatch reply'}`;
          feedback.className = 'reply-feedback text-[11px] font-medium text-rose-400 mt-1.5';
        }
      } catch (err) {
        feedback.textContent = `✗ Error: ${err.message}`;
        feedback.className = 'reply-feedback text-[11px] font-medium text-rose-400 mt-1.5';
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
  await loadNotificationsFeed();
}

function getAppBadgeStyle(appName, pkg) {
  const name = (appName || pkg || '').toLowerCase();
  if (name.includes('whatsapp')) return 'bg-emerald-500/10 text-emerald-400 border-emerald-500/20';
  if (name.includes('telegram')) return 'bg-sky-500/10 text-sky-400 border-sky-500/20';
  if (name.includes('slack')) return 'bg-purple-500/10 text-purple-400 border-purple-500/20';
  if (name.includes('signal')) return 'bg-blue-500/10 text-blue-400 border-blue-500/20';
  if (name.includes('sms') || name.includes('message')) return 'bg-amber-500/10 text-amber-400 border-amber-500/20';
  return 'bg-slate-700/30 text-slate-300 border-slate-700/50';
}

// -------------------------------------------------------------
// 4. Compose SMS with Base64 File Attachment
// -------------------------------------------------------------
function setupSmsComposer() {
  const form = document.getElementById('sms-form');
  const recipientInput = document.getElementById('sms-recipient');
  const bodyInput = document.getElementById('sms-body');
  const counter = document.getElementById('sms-char-counter');
  const fileInput = document.getElementById('sms-file-input');
  const dropzone = document.getElementById('dropzone');
  const dropzonePrompt = document.getElementById('dropzone-prompt');
  const previewBox = document.getElementById('attachment-preview');
  const previewName = document.getElementById('preview-name');
  const previewSize = document.getElementById('preview-size');
  const removeBtn = document.getElementById('btn-remove-attachment');
  const statusBanner = document.getElementById('sms-status-banner');
  const submitBtn = document.getElementById('btn-send-sms');
  const btnText = document.getElementById('btn-send-text');
  const btnSpinner = document.getElementById('btn-send-spinner');

  // Character counter
  bodyInput.addEventListener('input', () => {
    const len = bodyInput.value.length;
    const parts = Math.ceil(len / 160) || 1;
    counter.textContent = `${len} / 160 (${parts} SMS)`;
  });

  // Dropzone click
  dropzone.addEventListener('click', (e) => {
    if (e.target !== removeBtn && !removeBtn.contains(e.target)) {
      fileInput.click();
    }
  });

  // Drag and drop handlers
  dropzone.addEventListener('dragover', (e) => {
    e.preventDefault();
    dropzone.classList.add('border-sky-500', 'bg-sky-500/5');
  });

  dropzone.addEventListener('dragleave', () => {
    dropzone.classList.remove('border-sky-500', 'bg-sky-500/5');
  });

  dropzone.addEventListener('drop', (e) => {
    e.preventDefault();
    dropzone.classList.remove('border-sky-500', 'bg-sky-500/5');
    if (e.dataTransfer.files.length > 0) {
      handleFileSelected(e.dataTransfer.files[0]);
    }
  });

  fileInput.addEventListener('change', () => {
    if (fileInput.files.length > 0) {
      handleFileSelected(fileInput.files[0]);
    }
  });

  function handleFileSelected(file) {
    if (!file) return;

    const reader = new FileReader();
    reader.onload = () => {
      selectedAttachment = {
        name: file.name,
        type: file.type || 'application/octet-stream',
        size: file.size,
        base64: reader.result // Full data URL with base64 payload
      };

      previewName.textContent = file.name;
      previewSize.textContent = formatBytes(file.size);
      dropzonePrompt.classList.add('hidden');
      previewBox.classList.remove('hidden');
    };
    reader.readAsDataURL(file);
  }

  removeBtn.addEventListener('click', (e) => {
    e.stopPropagation();
    selectedAttachment = null;
    fileInput.value = '';
    previewBox.classList.add('hidden');
    dropzonePrompt.classList.remove('hidden');
  });

  // Form submit
  form.addEventListener('submit', async (e) => {
    e.preventDefault();
    const recipient = recipientInput.value.trim();
    const messageBody = bodyInput.value.trim();

    if (!recipient || !messageBody) return;

    submitBtn.disabled = true;
    btnText.textContent = 'Dispatching to Android...';
    btnSpinner.classList.remove('hidden');
    statusBanner.className = 'hidden';

    try {
      const response = await chrome.runtime.sendMessage({
        type: 'SEND_SMS_COMMAND',
        targetDeviceId: currentTargetDeviceId || 'android_companion',
        recipient,
        messageBody,
        attachment: selectedAttachment
      });

      if (response && response.success) {
        statusBanner.className = 'rounded-lg p-2.5 text-xs bg-emerald-500/10 text-emerald-400 border border-emerald-500/20';
        statusBanner.textContent = '✓ SMS command successfully pushed to Android phone.';
        form.reset();
        counter.textContent = '0 / 160';
        selectedAttachment = null;
        previewBox.classList.add('hidden');
        dropzonePrompt.classList.remove('hidden');
      } else {
        statusBanner.className = 'rounded-lg p-2.5 text-xs bg-rose-500/10 text-rose-400 border border-rose-500/20';
        statusBanner.textContent = `✗ Failed: ${response?.error || 'Could not dispatch SMS'}`;
      }
    } catch (err) {
      statusBanner.className = 'rounded-lg p-2.5 text-xs bg-rose-500/10 text-rose-400 border border-rose-500/20';
      statusBanner.textContent = `✗ Error: ${err.message}`;
    } finally {
      submitBtn.disabled = false;
      btnText.textContent = 'Send SMS via Phone';
      btnSpinner.classList.add('hidden');
    }
  });
}

// -------------------------------------------------------------
// 5. Universal Clipboard Sync (navigator.clipboard)
// -------------------------------------------------------------
function setupClipboardSync() {
  const phoneClipText = document.getElementById('phone-clip-text');
  const phoneClipTime = document.getElementById('phone-clip-time');
  const btnCopyToDesktop = document.getElementById('btn-copy-to-desktop');
  const btnReadDesktopClip = document.getElementById('btn-read-desktop-clip');
  const desktopClipInput = document.getElementById('desktop-clip-input');
  const btnPushClip = document.getElementById('btn-push-clip');
  const clipStatus = document.getElementById('clip-status-banner');

  // Copy phone snippet into desktop clipboard
  btnCopyToDesktop.addEventListener('click', async () => {
    const text = phoneClipText.getAttribute('data-raw-text') || phoneClipText.textContent;
    if (!text || text.includes('Waiting for text')) {
      showClipStatus('No phone clipboard text available to copy.', false);
      return;
    }

    try {
      await navigator.clipboard.writeText(text);
      showClipStatus('✓ Copied phone snippet to desktop clipboard!', true);
    } catch (err) {
      showClipStatus(`Failed to write to clipboard: ${err.message}`, false);
    }
  });

  // Read machine clipboard
  btnReadDesktopClip.addEventListener('click', async () => {
    try {
      const text = await navigator.clipboard.readText();
      desktopClipInput.value = text;
      showClipStatus('✓ Read current desktop clipboard into composer.', true);
    } catch (err) {
      showClipStatus(`Clipboard read permission required: ${err.message}`, false);
    }
  });

  // Push desktop clipboard to phone
  btnPushClip.addEventListener('click', async () => {
    const text = desktopClipInput.value.trim();
    if (!text) {
      showClipStatus('Please enter or paste text to sync.', false);
      return;
    }

    try {
      const res = await chrome.runtime.sendMessage({
        type: 'SYNC_CLIPBOARD_TO_DEVICE',
        targetDeviceId: currentTargetDeviceId || 'android_companion',
        text
      });

      if (res && res.success) {
        showClipStatus('✓ Pushed snippet to Android phone via Firebase!', true);
        desktopClipInput.value = '';
      } else {
        showClipStatus('Failed to sync clipboard.', false);
      }
    } catch (err) {
      showClipStatus(`Sync error: ${err.message}`, false);
    }
  });

  // Real-time clipboard update listener
  chrome.runtime.onMessage.addListener((message) => {
    if (message.type === 'CLIPBOARD_UPDATED') {
      loadClipboardState();
    }
  });

  function showClipStatus(msg, isSuccess) {
    clipStatus.textContent = msg;
    clipStatus.className = isSuccess
      ? 'rounded-lg p-2.5 text-xs bg-emerald-500/10 text-emerald-400 border border-emerald-500/20'
      : 'rounded-lg p-2.5 text-xs bg-rose-500/10 text-rose-400 border border-rose-500/20';
    setTimeout(() => clipStatus.classList.add('hidden'), 4000);
  }
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

// -------------------------------------------------------------
// 6. Diagnostics & Toast Alerts
// -------------------------------------------------------------
function setupDiagnostics() {
  document.getElementById('btn-test-toast')?.addEventListener('click', async () => {
    await chrome.runtime.sendMessage({ type: 'TEST_NOTIFICATION_ALERT' });
    await loadNotificationsFeed();
  });
}

// -------------------------------------------------------------
// Helper Utilities
// -------------------------------------------------------------
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
