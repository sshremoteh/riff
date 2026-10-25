package io.github.rootscripts.riff

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.*
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.util.concurrent.Executors

class RiffServer(private val context: Context, private val port: Int = 8888) {

    private var serverSocket: ServerSocket? = null
    private var isRunning = false
    private val executor = Executors.newFixedThreadPool(4)
    var onStateUpdate: ((title: String, artist: String, isPlaying: Boolean) -> Unit)? = null

    fun start() {
        if (isRunning) return
        try {
            serverSocket = ServerSocket(port, 50, java.net.InetAddress.getByName("127.0.0.1"))
            isRunning = true
            Thread {
                while (isRunning) {
                    try {
                        val client = serverSocket?.accept() ?: break
                        executor.execute { handleClient(client) }
                    } catch (_: Exception) {
                        break
                    }
                }
            }.start()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun stop() {
        isRunning = false
        try {
            serverSocket?.close()
        } catch (_: Exception) {}
        executor.shutdownNow()
    }

    private fun handleClient(socket: Socket) {
        try {
            val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
            val output = socket.getOutputStream()

            val requestLine = reader.readLine() ?: return
            val parts = requestLine.split(" ")
            if (parts.size < 2) return

            val method = parts[0]
            val fullPath = parts[1]
            val uri = Uri.parse(fullPath)
            val path = uri.path ?: "/"

            // Read headers
            var contentLength = 0
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                if (line.isNullOrEmpty()) break
                val lower = line!!.lowercase()
                if (lower.startsWith("content-length:")) {
                    contentLength = lower.substring("content-length:".length).trim().toIntOrNull() ?: 0
                }
            }

            // Read body if POST
            val bodyBuilder = StringBuilder()
            if (contentLength > 0) {
                val buf = CharArray(contentLength)
                var readTotal = 0
                while (readTotal < contentLength) {
                    val r = reader.read(buf, readTotal, contentLength - readTotal)
                    if (r == -1) break
                    readTotal += r
                }
                bodyBuilder.append(buf, 0, readTotal)
            }

            val body = bodyBuilder.toString()

            // Routing
            when {
                path == "/api/state" -> {
                    if (method == "POST" && body.isNotEmpty()) {
                        try {
                            val json = JSONObject(body)
                            val title = json.optString("title", "")
                            val artist = json.optString("artist", "")
                            val isPlaying = json.optBoolean("isPlaying", false)
                            onStateUpdate?.invoke(title, artist, isPlaying)
                        } catch (_: Exception) {}
                    }
                    sendJsonResponse(output, JSONObject().put("success", true).toString())
                }

                path == "/api/tools-status" -> {
                    val json = JSONObject().apply {
                        put("ytDlp", true)
                        put("ffmpeg", true)
                        put("status", "ready")
                        put("platform", "android")
                    }
                    sendJsonResponse(output, json.toString())
                }

                path == "/api/search" -> {
                    val q = uri.getQueryParameter("q") ?: ""
                    val limit = uri.getQueryParameter("limit")?.toIntOrNull() ?: 14
                    val tracks = MusicResolver.searchYouTubeMusic(q, limit)
                    val arr = JSONArray()
                    for (t in tracks) {
                        arr.put(t.toJson())
                    }
                    sendJsonResponse(output, arr.toString())
                }

                path == "/api/stream-info" -> {
                    val videoId = uri.getQueryParameter("trackId") ?: uri.getQueryParameter("id") ?: ""
                    val title = uri.getQueryParameter("title") ?: ""
                    val artist = uri.getQueryParameter("artist") ?: ""
                    val streamData = MusicResolver.resolveStreamUrl(videoId, title, artist)
                    sendJsonResponse(output, streamData.toString())
                }

                path == "/api/lyrics" -> {
                    val title = uri.getQueryParameter("title") ?: ""
                    val artist = uri.getQueryParameter("artist") ?: ""
                    val lyricsData = MusicResolver.getLyrics(title, artist)
                    sendJsonResponse(output, lyricsData.toString())
                }

                path == "/api/artist" -> {
                    val name = uri.getQueryParameter("name") ?: ""
                    val artistData = MusicResolver.getArtist(name)
                    sendJsonResponse(output, artistData.toString())
                }

                path == "/api/hot" || path == "/api/vibe" -> {
                    val hot = MusicResolver.searchYouTubeMusic("Top Music Hits 2026", 10)
                    val arr = JSONArray()
                    for (t in hot) arr.put(t.toJson())
                    sendJsonResponse(output, arr.toString())
                }

                path == "/api/audio-output-info" -> {
                    val json = JSONObject().apply {
                        put("current", "Default Audio Device")
                        put("devices", JSONArray())
                    }
                    sendJsonResponse(output, json.toString())
                }

                path == "/api/audio-proxy" -> {
                    val targetUrl = uri.getQueryParameter("url") ?: ""
                    if (targetUrl.startsWith("http")) {
                        proxyAudio(targetUrl, output)
                        return
                    } else {
                        sendResponse(output, 400, "text/plain", "Invalid target URL")
                    }
                }

                else -> {
                    sendResponse(output, 200, "application/json", "{\"status\":\"ok\"}")
                }
            }

        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            try { socket.close() } catch (_: Exception) {}
        }
    }

    private fun sendJsonResponse(out: OutputStream, json: String) {
        sendResponse(out, 200, "application/json; charset=utf-8", json)
    }

    private fun sendResponse(out: OutputStream, status: Int, contentType: String, content: String) {
        val bytes = content.toByteArray(Charsets.UTF_8)
        val header = "HTTP/1.1 $status OK\r\n" +
                "Content-Type: $contentType\r\n" +
                "Content-Length: ${bytes.size}\r\n" +
                "Access-Control-Allow-Origin: *\r\n" +
                "Access-Control-Allow-Headers: *\r\n" +
                "Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n" +
                "Connection: close\r\n\r\n"
        out.write(header.toByteArray(Charsets.UTF_8))
        out.write(bytes)
        out.flush()
    }

    private fun proxyAudio(targetUrl: String, clientOut: OutputStream) {
        try {
            val url = URL(targetUrl)
            val conn = url.openConnection() as HttpURLConnection
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14)")
            conn.connectTimeout = 8000
            conn.readTimeout = 8000

            val status = conn.responseCode
            val length = conn.contentLengthLong
            val mime = conn.contentType ?: "audio/mpeg"

            val header = "HTTP/1.1 $status OK\r\n" +
                    "Content-Type: $mime\r\n" +
                    (if (length > 0) "Content-Length: $length\r\n" else "") +
                    "Access-Control-Allow-Origin: *\r\n" +
                    "Accept-Ranges: bytes\r\n" +
                    "Connection: close\r\n\r\n"

            clientOut.write(header.toByteArray(Charsets.UTF_8))
            val buf = ByteArray(16384)
            val inStream = conn.inputStream
            var read: Int
            while (inStream.read(buf).also { read = it } != -1) {
                clientOut.write(buf, 0, read)
            }
            clientOut.flush()
        } catch (_: Exception) {}
    }
}
