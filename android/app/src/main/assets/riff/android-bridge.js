// Android Bridge polyfill for Riff
(() => {
  try {
    localStorage.setItem('tutorial_done', '1');
    localStorage.setItem('riff_platform', 'android');
    localStorage.setItem('riff_tools_installed', '1');
    sessionStorage.setItem('riff_tools_prompt_dismissed', '1');
  } catch (e) {}

  document.documentElement.classList.add('android-app');
  if (document.body) document.body.classList.add('android-app');

  window._pendingPickers = {};

  window.__onAudioPickerResult = function(reqId, items) {
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
    const rightPanel = document.getElementById('right-panel');
    if (rightPanel && rightPanel.classList.contains('mobile-open')) {
      rightPanel.classList.remove('mobile-open');
      return true;
    }
    const visibleModal = document.querySelector('.modal-backdrop.visible:not(.hidden)');
    if (visibleModal) {
      const btnClose = visibleModal.querySelector('.btn-close, [title="Close"], .modal-close, #btn-close-settings, #btn-close-tools-modal');
      if (btnClose) {
        btnClose.click();
      } else {
        visibleModal.classList.remove('visible');
        visibleModal.classList.add('hidden');
      }
      return true;
    }
    if (window.state && window.state.currentView && window.state.currentView !== 'discover') {
      if (typeof window.switchView === 'function') {
        window.switchView('discover');
        return true;
      }
    }
    return false;
  };

  // Setup DOM interactions on DOMContentLoaded
  document.addEventListener('DOMContentLoaded', () => {
    if (document.body) document.body.classList.add('android-app');

    // Connect native audio state to Android notification
    const audio = document.getElementById('native-audio') || document.querySelector('audio');
    if (audio) {
      const notify = () => {
        const titleEl = document.querySelector('.now-playing-title, #current-title, #playbar-title');
        const artistEl = document.querySelector('.now-playing-artist, #current-artist, #playbar-artist');
        const title = (titleEl && titleEl.textContent && titleEl.textContent !== 'No track playing') 
          ? titleEl.textContent 
          : 'Riff';
        const artist = (artistEl && artistEl.textContent && artistEl.textContent !== 'Select a track to listen') 
          ? artistEl.textContent 
          : 'Music';
        if (window.AndroidBridge && window.AndroidBridge.notifyState) {
          window.AndroidBridge.notifyState(title, artist, !audio.paused);
        }
      };
      audio.addEventListener('play', notify);
      audio.addEventListener('pause', notify);
      audio.addEventListener('ended', notify);
    }

    // Toggle mobile lyrics overlay when tapping lyrics button or playbar track meta
    const btnLyrics = document.getElementById('btn-toggle-lyrics-panel');
    const rightPanel = document.getElementById('right-panel');
    const btnCloseRightPanel = document.getElementById('btn-close-right-panel');
    const playbarMeta = document.querySelector('.playbar-left .track-meta');

    if (btnLyrics && rightPanel) {
      btnLyrics.addEventListener('click', (e) => {
        e.stopPropagation();
        rightPanel.classList.toggle('mobile-open');
      });
    }

    if (playbarMeta && rightPanel) {
      playbarMeta.addEventListener('click', () => {
        rightPanel.classList.add('mobile-open');
      });
    }

    if (btnCloseRightPanel && rightPanel) {
      btnCloseRightPanel.addEventListener('click', (e) => {
        e.stopPropagation();
        rightPanel.classList.remove('mobile-open');
      });
    }
  });
})();
