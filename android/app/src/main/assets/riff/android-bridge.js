// Android Bridge polyfill for Riff
(() => {
  try {
    localStorage.setItem('tutorial_done', '1');
    localStorage.setItem('riff_platform', 'android');
    // Ensure tools modal is allowed to prompt if not yet installed
    if (!localStorage.getItem('riff_tools_v2_done')) {
      sessionStorage.removeItem('riff_tools_prompt_dismissed');
      localStorage.removeItem('riff_tools_installed');
    }
  } catch (e) {}

  document.documentElement.classList.add('android-app');
  document.documentElement.classList.remove('mini');
  if (document.body) {
    document.body.classList.add('android-app');
    document.body.classList.remove('mini');
  }

  window._pendingPickers = {};

  window.__onAudioPickerResult = function(reqId, items) {
    try {
      if (typeof items === 'string') {
        items = JSON.parse(items);
      }
    } catch (_) {}
    if (window._pendingPickers && window._pendingPickers[reqId]) {
      window._pendingPickers[reqId](items || []);
      delete window._pendingPickers[reqId];
    }
  };

  window.electronAPI = {
    serverToken: 'riff-android-token',
    getServerPort: async () => {
      return (window.AndroidBridge && window.AndroidBridge.getServerPort) 
        ? window.AndroidBridge.getServerPort() 
        : 8888;
    },
    getGpuCompositing: async () => true,
    minimizeWindow: () => {
      if (window.AndroidBridge && window.AndroidBridge.minimizeWindow) {
        window.AndroidBridge.minimizeWindow();
      }
    },
    maximizeWindow: () => {},
    closeWindow: () => {
      if (window.AndroidBridge && window.AndroidBridge.closeWindow) {
        window.AndroidBridge.closeWindow();
      }
    },
    setZoomFactor: () => {},
    getZoomFactor: () => 1.0,
    getCacheSize: async () => 0,
    clearCache: async () => true,
    getDataDir: async () => '/data/data/io.github.rootscripts.riff',
    getDownloadDir: async () => '/sdcard/Music',
    getAppVersion: async () => {
      return (window.AndroidBridge && window.AndroidBridge.getAppVersion)
        ? window.AndroidBridge.getAppVersion()
        : '42.3.0-android';
    },
    checkForUpdates: async () => ({ upToDate: true }),
    getMultiInstance: async () => false,
    setMultiInstance: () => {},
    isWindows: false,
    isAndroid: true,
    setMediaKeys: () => {},
    mediaKeysActive: () => {},
    discordRpcSetEnabled: () => {},
    discordRpcUpdate: () => {},
    discordRpcClear: () => {},
    listThemes: async () => [],
    listPlugins: async () => [],
    getAudioOutputInfo: async () => ({ current: 'Default Audio (Speaker / Headset)', devices: [] }),
    logError: (level, msg) => console.log(`[Riff ${level}]`, msg),
    welcomeFinish: () => {},
    toggleMiniWindow: () => {
      // Disabled on mobile - mobile phones must never switch to desktop mini-widget layout
      document.body.classList.remove('mini');
    },

    // Local audio file picker
    importAudioFiles: async () => {
      return new Promise((resolve) => {
        const reqId = 'pick_' + Date.now() + '_' + Math.random().toString(36).slice(2);
        window._pendingPickers[reqId] = resolve;
        if (window.AndroidBridge && window.AndroidBridge.openAudioPicker) {
          window.AndroidBridge.openAudioPicker(reqId);
        } else {
          resolve([]);
        }
      });
    },

    pickMedia: async () => {
      return null;
    }
  };

  // Back button helper called from MainActivity
  window.__riffGoBack = function() {
    const tvk = document.getElementById('tv-karaoke');
    if (tvk && !tvk.classList.contains('hidden')) {
      const tvkPanel = document.getElementById('tvk-panel');
      if (tvkPanel && tvkPanel.classList.contains('open')) {
        tvkPanel.classList.remove('open');
        const btn = document.getElementById('btn-tvk-settings');
        if (btn) btn.classList.remove('open');
        return true;
      }
      if (typeof window.closeTvKaraoke === 'function') {
        window.closeTvKaraoke();
        return true;
      }
    }
    const rightPanel = document.getElementById('right-panel');
    if (rightPanel && rightPanel.classList.contains('mobile-open')) {
      rightPanel.classList.remove('mobile-open');
      document.body.classList.remove('right-panel-open', 'has-mobile-lyrics');
      return true;
    }
    const modal = document.querySelector('.modal-backdrop.visible:not(.hidden)');
    if (modal) {
      const closeBtn = modal.querySelector('.btn-close, [title="Close"], .modal-close, #btn-close-settings, #btn-close-tools-modal');
      if (closeBtn) {
        closeBtn.click();
        return true;
      }
      modal.classList.add('hidden');
      modal.classList.remove('visible');
      return true;
    }
    return false;
  };

  document.addEventListener('DOMContentLoaded', () => {
    if (document.body) {
      document.body.classList.add('android-app');
      document.body.classList.remove('mini');
    }

    const audio = document.getElementById('native-audio') || document.querySelector('audio');
    if (audio) {
      audio.removeAttribute('crossorigin');

      const notify = () => {
        const titleEl = document.querySelector('.now-playing-title, #current-title, #playbar-title');
        const artistEl = document.querySelector('.now-playing-artist, #current-artist, #playbar-artist');
        const title = titleEl ? (titleEl.textContent || '').trim() : '';
        const artist = artistEl ? (artistEl.textContent || '').trim() : '';
        const isPlaying = !audio.paused && !audio.ended && audio.currentTime > 0;

        if (window.AndroidBridge && window.AndroidBridge.notifyState) {
          window.AndroidBridge.notifyState(title, artist, isPlaying);
        }
      };

      audio.addEventListener('play', notify);
      audio.addEventListener('pause', notify);
      audio.addEventListener('ended', notify);
    }

    // Toggle mobile lyrics overlay ONLY when clicking lyrics icon
    const btnLyrics = document.getElementById('btn-toggle-lyrics-panel');
    const rightPanel = document.getElementById('right-panel');
    const btnCloseRightPanel = document.getElementById('btn-close-right-panel');

    const setRightPanelOpen = (open) => {
      if (!rightPanel) return;
      rightPanel.classList.toggle('mobile-open', open);
      rightPanel.classList.toggle('hidden', !open);
      document.body.classList.toggle('right-panel-open', open);
      document.body.classList.toggle('has-mobile-lyrics', open);
    };

    if (btnLyrics && rightPanel) {
      btnLyrics.addEventListener('click', () => {
        const isOpen = !rightPanel.classList.contains('mobile-open');
        setRightPanelOpen(isOpen);
      });
    }

    if (btnCloseRightPanel && rightPanel) {
      btnCloseRightPanel.addEventListener('click', () => {
        setRightPanelOpen(false);
      });
    }

    // Tap on bottom playbar opens the menu that appears when clicking the button with the note
    const playbar = document.getElementById('playbar');
    if (playbar && btnLyrics) {
      playbar.addEventListener('click', (e) => {
        if (e.target.closest('#btn-play-pause, #btn-prev, #btn-next, #btn-shuffle, #btn-repeat, #btn-toggle-fav, #btn-playbar-more, #btn-open-settings, #scrubber-track, .control-btn')) {
          return;
        }
        btnLyrics.click();
      });
    }

    // Ensure track rows on touch screens respond instantly to tap without delay
    let touchStartY = 0;
    let touchStartX = 0;
    let touchTarget = null;
    document.addEventListener('touchstart', (e) => {
      const row = e.target.closest('.track-row');
      if (row && !e.target.closest('.track-row-actions, button, input')) {
        touchStartY = e.touches[0].clientY;
        touchStartX = e.touches[0].clientX;
        touchTarget = row;
      } else {
        touchTarget = null;
      }
    }, { passive: true });

    document.addEventListener('touchend', (e) => {
      if (!touchTarget) return;
      const endY = e.changedTouches[0].clientY;
      const endX = e.changedTouches[0].clientX;
      if (Math.abs(endY - touchStartY) < 12 && Math.abs(endX - touchStartX) < 12) {
        touchTarget.click();
      }
      touchTarget = null;
    }, { passive: true });

    // Wake up TV Karaoke controls on touch
    const tvKaraoke = document.getElementById('tv-karaoke');
    if (tvKaraoke) {
      const wake = () => {
        tvKaraoke.classList.remove('tv-idle');
        if (typeof window.resetTvIdleTimer === 'function') {
          window.resetTvIdleTimer();
        }
      };
      tvKaraoke.addEventListener('touchstart', wake, { passive: true });
      tvKaraoke.addEventListener('pointerdown', wake, { passive: true });
    }
  });
})();
