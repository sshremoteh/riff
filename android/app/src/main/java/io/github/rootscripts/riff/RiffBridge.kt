package io.github.rootscripts.riff

import android.webkit.JavascriptInterface
import android.widget.Toast

class RiffBridge(private val activity: MainActivity) {

    @JavascriptInterface
    fun getServerPort(): Int = 8888

    @JavascriptInterface
    fun minimizeWindow() {
        activity.runOnUiThread {
            activity.moveTaskToBack(true)
        }
    }

    @JavascriptInterface
    fun closeWindow() {
        activity.runOnUiThread {
            activity.finish()
        }
    }

    @JavascriptInterface
    fun showToast(message: String) {
        activity.runOnUiThread {
            Toast.makeText(activity, message, Toast.LENGTH_SHORT).show()
        }
    }

    @JavascriptInterface
    fun notifyState(title: String, artist: String, isPlaying: Boolean) {
        activity.runOnUiThread {
            activity.updateMediaState(title, artist, isPlaying)
        }
    }

    @JavascriptInterface
    fun openAudioPicker(reqId: String) {
        activity.runOnUiThread {
            activity.launchAudioPicker(reqId)
        }
    }

    @JavascriptInterface
    fun getAppVersion(): String = "42.3.0-android"
}
