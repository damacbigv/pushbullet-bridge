/**
 * PushBridge Options & Settings Controller
 */

document.addEventListener('DOMContentLoaded', async () => {
  const form = document.getElementById('settings-form');
  const relayUrlInput = document.getElementById('relay-url');
  const notifToggle = document.getElementById('notif-toggle');
  const clipboardToggle = document.getElementById('clipboard-toggle');
  const projectIdInput = document.getElementById('firebase-project-id');
  const saveStatus = document.getElementById('save-status');

  // Load current values
  const config = await chrome.storage.local.get([
    'relayServerUrl',
    'notificationsEnabled',
    'clipboardSyncEnabled',
    'firebaseProjectId'
  ]);

  projectIdInput.value = config.firebaseProjectId || 'pushclonebridge';
  relayUrlInput.value = config.relayServerUrl || 'http://localhost:3000';
  notifToggle.checked = config.notificationsEnabled ?? true;
  clipboardToggle.checked = config.clipboardSyncEnabled ?? true;

  // Save handler
  form.addEventListener('submit', async (e) => {
    e.preventDefault();

    await chrome.storage.local.set({
      relayServerUrl: relayUrlInput.value.trim(),
      notificationsEnabled: notifToggle.checked,
      clipboardSyncEnabled: clipboardToggle.checked
    });

    saveStatus.classList.remove('hidden');
    setTimeout(() => {
      saveStatus.classList.add('hidden');
    }, 2500);
  });
});
