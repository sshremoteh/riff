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
    private val executor = Executors.newFixedThreadPool(6)
    var onStateUpdate: ((title: String, artist: String, isPlaying: Boolean) -> Unit)? = null

    private val libraryDir: File by lazy {
        File(context.filesDir, "library").apply { mkdirs() }
    }
    private val libraryIndexFile: File by lazy {
        File(libraryDir, "libraryIndex.json")
    }

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
            var rangeHeader: String? = null
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                if (line.isNullOrEmpty()) break
                val lower = line!!.lowercase()
                if (lower.startsWith("content-length:")) {
                    contentLength = lower.substring("content-length:".length).trim().toIntOrNull() ?: 0
                } else if (lower.startsWith("range:")) {
                    rangeHeader = line!!.substring("range:".length).trim()
                }
            }

            // Read body if present
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
                    val prog = JSONObject().apply {
                        put("status", "done")
                        put("tool", "")
                        put("percent", 100)
                        put("error", "")
                    }
                    val json = JSONObject().apply {
                        put("ytDlp", true)
                        put("ffmpeg", true)
                        put("status", "ready")
                        put("platform", "android")
                        put("progress", prog)
                    }
                    sendJsonResponse(output, json.toString())
                }

                path == "/api/tools-install" -> {
                    val json = JSONObject().apply {
                        put("started", true)
                        put("status", "done")
                    }
                    sendJsonResponse(output, json.toString())
                }

                path == "/api/search" -> {
                    val q = uri.getQueryParameter("q") ?: ""
                    val limit = uri.getQueryParameter("limit")?.toIntOrNull() ?: 20
                    val tracks = MusicResolver.searchYouTubeMusic(q, limit)
                    val arr = JSONArray()
                    for (t in tracks) {
                        arr.put(t.toJson())
                    }
                    val resp = JSONObject().apply {
                        put("success", true)
                        put("tracks", arr)
                    }
                    sendJsonResponse(output, resp.toString())
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
                    val hot = MusicResolver.searchYouTubeMusic("Top Music Hits 2026", 14)
                    val arr = JSONArray()
                    for (t in hot) arr.put(t.toJson())
                    val resp = JSONObject().apply {
                        put("success", true)
                        put("tracks", arr)
                    }
                    sendJsonResponse(output, resp.toString())
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

                // --- Local Library Endpoints ---
                path == "/api/library" -> {
                    val list = getLibraryTracks()
                    sendJsonResponse(output, list.toString())
                }

                path == "/api/library-add" && method == "POST" -> {
                    try {
                        val payload = JSONObject(body)
                        val items = payload.optJSONArray("items") ?: JSONArray()
                        addLibraryItems(items)
                        sendJsonResponse(output, JSONObject().put("success", true).toString())
                    } catch (e: Exception) {
                        sendJsonResponse(output, JSONObject().put("success", false).put("error", e.message).toString())
                    }
                }

                path == "/api/library-update" && method == "POST" -> {
                    try {
                        val payload = JSONObject(body)
                        val id = payload.optString("id", "")
                        val duration = payload.optInt("duration", 0)
                        if (id.isNotEmpty() && duration > 0) {
                            updateLibraryItemDuration(id, duration)
                        }
                        sendJsonResponse(output, JSONObject().put("success", true).toString())
                    } catch (e: Exception) {
                        sendJsonResponse(output, JSONObject().put("success", false).put("error", e.message).toString())
                    }
                }

                path == "/api/library-remove" && method == "POST" -> {
                    try {
                        val payload = JSONObject(body)
                        val id = payload.optString("id", uri.getQueryParameter("id") ?: "")
                        if (id.isNotEmpty()) {
                            removeLibraryItem(id)
                        }
                        sendJsonResponse(output, JSONObject().put("success", true).toString())
                    } catch (e: Exception) {
                        sendJsonResponse(output, JSONObject().put("success", false).put("error", e.message).toString())
                    }
                }

                path == "/api/library-file" -> {
                    val id = uri.getQueryParameter("id") ?: ""
                    serveLibraryFile(id, rangeHeader, output)
                    return
                }

                path == "/api/local-cover" -> {
                    val id = uri.getQueryParameter("id") ?: ""
                    serveLocalCover(id, output)
                    return
                }

                path == "/api/check-cache" -> {
                    sendJsonResponse(output, JSONObject().put("cached", false).toString())
                }

                path == "/api/offline-library" -> {
                    sendJsonResponse(output, JSONArray().toString())
                }

                path == "/api/clear-offline-library" -> {
                    sendJsonResponse(output, JSONObject().put("success", true).toString())
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

    // --- Library Helper Methods ---

    @Synchronized
    private fun getLibraryIndex(): JSONObject {
        return try {
            if (libraryIndexFile.exists()) {
                val text = libraryIndexFile.readText(Charsets.UTF_8)
                JSONObject(text)
            } else {
                JSONObject()
            }
        } catch (_: Exception) {
            JSONObject()
        }
    }

    @Synchronized
    private fun saveLibraryIndex(index: JSONObject) {
        try {
            libraryIndexFile.writeText(index.toString(2), Charsets.UTF_8)
        } catch (_: Exception) {}
    }

    @Synchronized
    private fun addLibraryItems(items: JSONArray) {
        val index = getLibraryIndex()
        for (i in 0 until items.length()) {
            val item = items.optJSONObject(i) ?: continue
            val id = item.optString("id", "")
            if (id.isEmpty()) continue
            index.put(id, item)
        }
        saveLibraryIndex(index)
    }

    @Synchronized
    private fun updateLibraryItemDuration(id: String, duration: Int) {
        val index = getLibraryIndex()
        if (index.has(id)) {
            val item = index.getJSONObject(id)
            item.put("duration", duration)
            index.put(id, item)
            saveLibraryIndex(index)
        }
    }

    @Synchronized
    private fun removeLibraryItem(id: String) {
        val index = getLibraryIndex()
        if (index.has(id)) {
            val item = index.optJSONObject(id)
            val file = item?.optString("file", "") ?: ""
            index.remove(id)
            saveLibraryIndex(index)

            // Delete audio file and cover art
            if (file.isNotEmpty()) {
                File(libraryDir, file).delete()
            }
            File(libraryDir, "$id.jpg").delete()
        }
    }

    private fun getLibraryTracks(): JSONArray {
        val index = getLibraryIndex()
        val arr = JSONArray()
        val keys = index.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            val item = index.getJSONObject(k)
            val id = item.optString("id", k)
            val coverFile = File(libraryDir, "$id.jpg")
            val thumbUrl = if (coverFile.exists()) {
                "http://127.0.0.1:$port/api/local-cover?id=${Uri.encode(id)}"
            } else {
                ""
            }

            val track = JSONObject().apply {
                put("id", id)
                put("platform", "local")
                put("title", item.optString("title", item.optString("name", "Untitled")))
                put("artist", item.optString("artist", "Unknown artist"))
                put("duration", item.optInt("duration", 0))
                put("thumbnail", thumbUrl)
                put("file", item.optString("file", ""))
            }
            arr.put(track)
        }
        return arr
    }

    private fun serveLibraryFile(id: String, rangeHeader: String?, out: OutputStream) {
        val index = getLibraryIndex()
        val item = if (index.has(id)) index.optJSONObject(id) else null
        val fileName = item?.optString("file", "") ?: ""

        var targetFile = if (fileName.isNotEmpty()) File(libraryDir, fileName) else null
        if (targetFile == null || !targetFile.exists()) {
            // Find file starting with id in libraryDir
            val matches = libraryDir.listFiles { _, name -> name.startsWith(id) && !name.endsWith(".jpg") }
            if (!matches.isNullOrEmpty()) {
                targetFile = matches[0]
            }
        }

        if (targetFile == null || !targetFile.exists()) {
            sendResponse(out, 404, "text/plain", "File not found")
            return
        }

        val ext = targetFile.extension.lowercase()
        val mime = when (ext) {
            "mp3" -> "audio/mpeg"
            "flac" -> "audio/flac"
            "wav" -> "audio/wav"
            "m4a", "aac" -> "audio/mp4"
            "ogg", "opus", "oga" -> "audio/ogg"
            "weba", "webm" -> "audio/webm"
            else -> "audio/mpeg"
        }

        val fileLength = targetFile.length()

        if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
            val rangeSpec = rangeHeader.substring("bytes=".length).trim()
            val rangeParts = rangeSpec.split("-")
            val start = rangeParts[0].toLongOrNull() ?: 0L
            val end = if (rangeParts.size > 1 && rangeParts[1].isNotEmpty()) {
                rangeParts[1].toLongOrNull() ?: (fileLength - 1)
            } else {
                fileLength - 1
            }
            val clampedEnd = minOf(end, fileLength - 1)
            val contentLength = clampedEnd - start + 1

            val header = "HTTP/1.1 206 Partial Content\r\n" +
                    "Content-Type: $mime\r\n" +
                    "Content-Length: $contentLength\r\n" +
                    "Content-Range: bytes $start-$clampedEnd/$fileLength\r\n" +
                    "Accept-Ranges: bytes\r\n" +
                    "Access-Control-Allow-Origin: *\r\n" +
                    "Connection: close\r\n\r\n"
            out.write(header.toByteArray(Charsets.UTF_8))

            RandomAccessFile(targetFile, "r").use { raf ->
                raf.seek(start)
                val buffer = ByteArray(16384)
                var remaining = contentLength
                while (remaining > 0) {
                    val toRead = minOf(buffer.size.toLong(), remaining).toInt()
                    val r = raf.read(buffer, 0, toRead)
                    if (r == -1) break
                    out.write(buffer, 0, r)
                    remaining -= r
                }
            }
        } else {
            val header = "HTTP/1.1 200 OK\r\n" +
                    "Content-Type: $mime\r\n" +
                    "Content-Length: $fileLength\r\n" +
                    "Accept-Ranges: bytes\r\n" +
                    "Access-Control-Allow-Origin: *\r\n" +
                    "Connection: close\r\n\r\n"
            out.write(header.toByteArray(Charsets.UTF_8))

            FileInputStream(targetFile).use { fis ->
                val buffer = ByteArray(16384)
                var read: Int
                while (fis.read(buffer).also { read = it } != -1) {
                    out.write(buffer, 0, read)
                }
            }
        }
        out.flush()
    }

    private fun serveLocalCover(id: String, out: OutputStream) {
        val coverFile = File(libraryDir, "$id.jpg")
        if (!coverFile.exists()) {
            sendResponse(out, 404, "text/plain", "Cover not found")
            return
        }

        val length = coverFile.length()
        val header = "HTTP/1.1 200 OK\r\n" +
                "Content-Type: image/jpeg\r\n" +
                "Content-Length: $length\r\n" +
                "Cache-Control: public, max-age=86400\r\n" +
                "Access-Control-Allow-Origin: *\r\n" +
                "Connection: close\r\n\r\n"
        out.write(header.toByteArray(Charsets.UTF_8))

        FileInputStream(coverFile).use { fis ->
            val buffer = ByteArray(16384)
            var read: Int
            while (fis.read(buffer).also { read = it } != -1) {
                out.write(buffer, 0, read)
            }
        }
        out.flush()
    }
}
