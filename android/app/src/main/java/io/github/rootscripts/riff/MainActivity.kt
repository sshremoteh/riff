package io.github.rootscripts.riff

import android.annotation.SuppressLint
import android.content.*
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.OpenableColumns
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.webkit.*
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    companion object {
        var instance: MainActivity? = null
    }

    private lateinit var webView: WebView
    private var riffServer: RiffServer? = null
    private var audioService: RiffAudioService? = null
    private var isBound = false
    private lateinit var audioManager: AudioManager
    private var audioFocusRequest: AudioFocusRequest? = null
    private val backgroundExecutor = Executors.newSingleThreadExecutor()

    private var pendingAudioPickerReqId: String? = null

    private val audioPickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris: List<Uri>? ->
        val reqId = pendingAudioPickerReqId ?: return@registerForActivityResult
        pendingAudioPickerReqId = null

        backgroundExecutor.execute {
            val itemsJson = processSelectedUris(uris ?: emptyList())
            runOnUiThread {
                webView.evaluateJavascript(
                    "if (window.__onAudioPickerResult) { window.__onAudioPickerResult('$reqId', $itemsJson); }",
                    null
                )
            }
        }
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as RiffAudioService.LocalBinder
            audioService = binder.getService()
            isBound = true
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            audioService = null
            isBound = false
        }
    }

    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
                dispatchJsCommand("pause")
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        instance = this

        // Hide top status bar and enable immersive full screen
        hideSystemBars()

        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        requestAudioFocus()

        // Start internal background server
        riffServer = RiffServer(this, 8888).apply {
            onStateUpdate = { title, artist, isPlaying ->
                runOnUiThread {
                    updateMediaState(title, artist, isPlaying)
                }
            }
            start()
        }

        // Start audio foreground service
        val serviceIntent = Intent(this, RiffAudioService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
        bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE)

        // Register noisy receiver (headphones unplugged)
        registerReceiver(noisyReceiver, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY))

        // Set up WebView
        webView = WebView(this).apply {
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                mediaPlaybackRequiresUserGesture = false
                allowFileAccess = true
                allowContentAccess = true
                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                cacheMode = WebSettings.LOAD_DEFAULT
                useWideViewPort = true
                loadWithOverviewMode = true
            }

            addJavascriptInterface(RiffBridge(this@MainActivity), "AndroidBridge")

            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                    val url = request?.url?.toString() ?: return false
                    if (url.startsWith("file://") || url.startsWith("http://127.0.0.1")) {
                        return false
                    }
                    return false
                }
            }

            webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(message: ConsoleMessage?): Boolean {
                    return super.onConsoleMessage(message)
                }
            }
        }

        setContentView(webView)

        // Load the Riff web app from assets
        webView.loadUrl("file:///android_asset/riff/index.html")

        // Handle Android Back button gracefully
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                webView.evaluateJavascript(
                    """
                    (function() {
                        var tvk = document.getElementById('tv-karaoke');
                        if (tvk && !tvk.classList.contains('hidden')) {
                            var tvkPanel = document.getElementById('tvk-panel');
                            if (tvkPanel && tvkPanel.classList.contains('open')) {
                                tvkPanel.classList.remove('open');
                                var btn = document.getElementById('btn-tvk-settings');
                                if (btn) btn.classList.remove('open');
                                return true;
                            }
                            if (typeof window.closeTvKaraoke === 'function') {
                                window.closeTvKaraoke();
                                return true;
                            }
                        }
                        if (window.__riffGoBack && window.__riffGoBack()) {
                            return true;
                        }
                        var modal = document.querySelector('.modal-backdrop.visible:not(.hidden)');
                        if (modal) {
                            var closeBtn = modal.querySelector('.btn-close, [title="Close"], .modal-close, #btn-close-settings, #btn-close-tools-modal');
                            if (closeBtn) { closeBtn.click(); return true; }
                            modal.classList.add('hidden');
                            modal.classList.remove('visible');
                            return true;
                        }
                        var panel = document.getElementById('right-panel');
                        if (panel && panel.classList.contains('mobile-open')) {
                            panel.classList.remove('mobile-open');
                            return true;
                        }
                        return false;
                    })()
                    """.trimIndent()
                ) { result ->
                    if (result != "true") {
                        moveTaskToBack(true)
                    }
                }
            }
        })
    }

    override fun onResume() {
        super.onResume()
        hideSystemBars()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            hideSystemBars()
        }
    }

    private fun hideSystemBars() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.let { controller ->
                controller.hide(WindowInsets.Type.statusBars())
                controller.systemBarsBehavior =
                    WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            )
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        }
    }

    fun launchAudioPicker(reqId: String) {
        pendingAudioPickerReqId = reqId
        audioPickerLauncher.launch(
            arrayOf(
                "audio/*",
                "application/ogg",
                "application/x-flac"
            )
        )
    }

    private fun processSelectedUris(uris: List<Uri>): String {
        val results = JSONArray()
        val libraryDir = File(filesDir, "library").apply { mkdirs() }

        for (uri in uris) {
            try {
                var fileName = "track.mp3"
                contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIdx != -1 && cursor.moveToFirst()) {
                        fileName = cursor.getString(nameIdx) ?: "track.mp3"
                    }
                }

                val ext = fileName.substringAfterLast('.', "mp3").lowercase()
                val id = "local_" + UUID.randomUUID().toString().replace("-", "").take(12)
                val destFile = File(libraryDir, "$id.$ext")

                contentResolver.openInputStream(uri)?.use { inStream ->
                    FileOutputStream(destFile).use { outStream ->
                        inStream.copyTo(outStream)
                    }
                }

                var title = fileName.substringBeforeLast('.')
                var artist = "Unknown artist"
                var duration = 0L

                try {
                    val mmr = MediaMetadataRetriever()
                    mmr.setDataSource(destFile.absolutePath)
                    val t = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                    val a = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                    val d = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    val art = mmr.embeddedPicture

                    if (!t.isNullOrBlank()) title = t
                    if (!a.isNullOrBlank()) artist = a
                    if (!d.isNullOrBlank()) {
                        duration = (d.toLongOrNull() ?: 0L) / 1000
                    }
                    if (art != null && art.isNotEmpty()) {
                        File(libraryDir, "$id.jpg").writeBytes(art)
                    }
                    mmr.release()
                } catch (_: Exception) {}

                val item = JSONObject().apply {
                    put("id", id)
                    put("file", "$id.$ext")
                    put("ext", ext)
                    put("name", title)
                    put("title", title)
                    put("artist", artist)
                    put("duration", duration)
                }
                results.put(item)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        return results.toString()
    }

    private fun requestAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val playbackAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()

            audioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(playbackAttributes)
                .setAcceptsDelayedFocusGain(true)
                .setOnAudioFocusChangeListener { focusChange ->
                    when (focusChange) {
                        AudioManager.AUDIOFOCUS_LOSS,
                        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> dispatchJsCommand("pause")
                        AudioManager.AUDIOFOCUS_GAIN -> dispatchJsCommand("play")
                    }
                }
                .build()

            audioManager.requestAudioFocus(audioFocusRequest!!)
        }
    }

    fun updateMediaState(title: String, artist: String, isPlaying: Boolean) {
        audioService?.updateNotification(title, artist, isPlaying)
    }

    fun handleMediaAction(action: String) {
        when (action) {
            RiffAudioService.ACTION_PLAY -> dispatchJsCommand("play")
            RiffAudioService.ACTION_PAUSE -> dispatchJsCommand("pause")
            RiffAudioService.ACTION_NEXT -> dispatchJsCommand("next")
            RiffAudioService.ACTION_PREV -> dispatchJsCommand("prev")
        }
    }

    private fun dispatchJsCommand(cmd: String) {
        val script = when (cmd) {
            "play" -> "document.querySelector('.play-btn, #btn-play, #btn-play-pause')?.click();"
            "pause" -> "document.querySelector('.play-btn, #btn-play, #btn-play-pause')?.click();"
            "next" -> "document.querySelector('.next-btn, #btn-next')?.click();"
            "prev" -> "document.querySelector('.prev-btn, #btn-prev')?.click();"
            else -> ""
        }
        if (script.isNotEmpty()) {
            runOnUiThread {
                webView.evaluateJavascript(script, null)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        try { unregisterReceiver(noisyReceiver) } catch (_: Exception) {}
        if (isBound) {
            unbindService(serviceConnection)
            isBound = false
        }
        riffServer?.stop()
        backgroundExecutor.shutdownNow()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && audioFocusRequest != null) {
            audioManager.abandonAudioFocusRequest(audioFocusRequest!!)
        }
    }
}
