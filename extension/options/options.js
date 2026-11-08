/**
 * PushBridge Options & Settings Controller (v3.0.3)
 */

document.addEventListener('DOMContentLoaded', async () => {
  const form = document.getElementById('settings-form');
  const relayUrlInput = document.getElementById('relay-url');
  const notifToggle = document.getElementById('notif-toggle');
  const clipboardToggle = document.getElementById('clipboard-toggle');
  const projectIdInput = document.getElementById('firebase-project-id');
  const saveStatus = document.getElementById('save-status');
  const themeCards = document.querySelectorAll('.theme-card');

  // Load current settings
  const config = await chrome.storage.local.get([
    'relayServerUrl',
    'notificationsEnabled',
    'clipboardSyncEnabled',
    'firebaseProjectId',
    'selectedTheme'
  ]);

  let currentTheme = config.selectedTheme || 'midnight';
  applyTheme(currentTheme);

  projectIdInput.value = config.firebaseProjectId || 'pushclonebridge';
  relayUrlInput.value = config.relayServerUrl || 'http://localhost:3000';
  notifToggle.checked = config.notificationsEnabled ?? true;
  clipboardToggle.checked = config.clipboardSyncEnabled ?? true;

  function applyTheme(theme) {
    currentTheme = theme;
    document.documentElement.setAttribute('data-theme', theme);
    document.body.setAttribute('data-theme', theme);

    themeCards.forEach(card => {
      if (card.getAttribute('data-theme-val') === theme) {
        card.classList.add('active');
      } else {
        card.classList.remove('active');
      }
    });
  }

  // Handle theme card clicks
  themeCards.forEach(card => {
    card.addEventListener('click', async () => {
      const theme = card.getAttribute('data-theme-val');
      if (!theme) return;

      applyTheme(theme);
      await chrome.storage.local.set({ selectedTheme: theme });

      chrome.runtime.sendMessage({
        type: 'THEME_CHANGED',
        theme
      }).catch(() => {});
    });
  });

  // Save handler
  form.addEventListener('submit', async (e) => {
    e.preventDefault();

    await chrome.storage.local.set({
      relayServerUrl: relayUrlInput.value.trim(),
      notificationsEnabled: notifToggle.checked,
      clipboardSyncEnabled: clipboardToggle.checked,
      selectedTheme: currentTheme
    });

    saveStatus.classList.remove('hidden');
    setTimeout(() => {
      saveStatus.classList.add('hidden');
    }, 2500);
  });
});
