// Android Bridge polyfill for Riff
(() => {
  try {
    localStorage.setItem('tutorial_done', '1');
    localStorage.setItem('riff_platform', 'android');
  } catch (e) {}

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
    welcomeFinish: () => {}
  };

  // Connect native audio state to Android notification
  document.addEventListener('DOMContentLoaded', () => {
    const audio = document.getElementById('native-audio') || document.querySelector('audio');
    if (audio) {
      const notify = () => {
        const titleEl = document.querySelector('.now-playing-title, #current-title');
        const artistEl = document.querySelector('.now-playing-artist, #current-artist');
        const title = titleEl ? titleEl.textContent : 'Riff';
        const artist = artistEl ? artistEl.textContent : 'Music';
        if (window.AndroidBridge && window.AndroidBridge.notifyState) {
          window.AndroidBridge.notifyState(title, artist, !audio.paused);
        }
      };
      audio.addEventListener('play', notify);
      audio.addEventListener('pause', notify);
      audio.addEventListener('ended', notify);
    }
  });
})();
