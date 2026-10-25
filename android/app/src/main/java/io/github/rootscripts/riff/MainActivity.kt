package io.github.rootscripts.riff

import android.annotation.SuppressLint
import android.content.*
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.webkit.*
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity

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
                // Headphones unplugged: pause playback
                dispatchJsCommand("pause")
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        instance = this

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

        // Register noisy receiver
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
                    // Keep file and local requests inside WebView
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

        // Handle Back button
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // Try closing open modal or sheet first via JavaScript
                webView.evaluateJavascript(
                    """
                    (function() {
                        var modal = document.querySelector('.modal-overlay.open, .sheet.open, .right-panel:not(.is-collapsed)');
                        if (modal) {
                            var closeBtn = modal.querySelector('.btn-close, .modal-close');
                            if (closeBtn) { closeBtn.click(); return true; }
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
            "play" -> "document.querySelector('.play-btn, #btn-play')?.click();"
            "pause" -> "document.querySelector('.play-btn, #btn-play')?.click();"
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && audioFocusRequest != null) {
            audioManager.abandonAudioFocusRequest(audioFocusRequest!!)
        }
    }
}
