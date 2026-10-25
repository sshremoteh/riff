package io.github.rootscripts.riff

import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class TrackItem(
    val id: String,
    val title: String,
    val artist: String,
    val duration: Int,
    val thumbnail: String,
    val url: String,
    val platform: String = "youtube"
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("title", title)
        put("artist", artist)
        put("duration", duration)
        put("thumbnail", thumbnail)
        put("url", url)
        put("platform", platform)
    }
}

object MusicResolver {

    private const val YTM_SEARCH_URL = "https://music.youtube.com/youtubei/v1/search?prettyPrint=false"
    private const val YT_PLAYER_URL = "https://www.youtube.com/youtubei/v1/player?prettyPrint=false"
    private const val LRCLIB_URL = "https://lrclib.net/api/get"
    private const val DEEZER_URL = "https://api.deezer.com/search"
    private const val SC_CLIENT_ID = "3GKD969NJWD1eB6gvQJN0GnueYbcWszv"

    fun searchYouTubeMusic(query: String, limit: Int = 20): List<TrackItem> {
        val tracks = mutableListOf<TrackItem>()
        try {
            val url = URL(YTM_SEARCH_URL)
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Origin", "https://music.youtube.com")
            conn.setRequestProperty("Referer", "https://music.youtube.com/")
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36")

            val payload = JSONObject().apply {
                val context = JSONObject().apply {
                    val client = JSONObject().apply {
                        put("clientName", "WEB_REMIX")
                        put("clientVersion", "1.20250101.01.00")
                        put("hl", "en")
                        put("gl", "US")
                    }
                    put("client", client)
                }
                put("context", context)
                put("query", query)
                put("params", "EgWKAQIIAWoMEA4QChADEAQQCRAF")
            }

            OutputStreamWriter(conn.outputStream, "UTF-8").use {
                it.write(payload.toString())
                it.flush()
            }

            if (conn.responseCode == 200) {
                val response = conn.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(response)
                tracks.addAll(parseYtmResponse(json, limit))
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // Fallback to Deezer API if YTM search was empty or failed
        if (tracks.isEmpty()) {
            tracks.addAll(searchDeezer(query, limit))
        }

        return tracks
    }

    private fun parseYtmResponse(json: JSONObject, limit: Int): List<TrackItem> {
        val results = mutableListOf<TrackItem>()
        try {
            val tabs = json.optJSONObject("contents")
                ?.optJSONObject("tabbedSearchResultsRenderer")
                ?.optJSONArray("tabs") ?: return emptyList()

            val tab = tabs.optJSONObject(0) ?: return emptyList()
            val sections = tab.optJSONObject("tabRenderer")
                ?.optJSONObject("content")
                ?.optJSONObject("sectionListRenderer")
                ?.optJSONArray("contents") ?: return emptyList()

            for (i in 0 until sections.length()) {
                val section = sections.optJSONObject(i) ?: continue
                val items = section.optJSONObject("musicShelfRenderer")?.optJSONArray("contents") ?: continue

                for (j in 0 until items.length()) {
                    if (results.size >= limit) break
                    val item = items.optJSONObject(j)?.optJSONObject("musicResponsiveListItemRenderer") ?: continue

                    var videoId = item.optJSONObject("playlistItemData")?.optString("videoId", "") ?: ""
                    if (videoId.isEmpty()) {
                        val overlay = item.optJSONObject("overlay")
                            ?.optJSONObject("musicItemThumbnailOverlayRenderer")
                            ?.optJSONObject("content")
                            ?.optJSONObject("musicPlayButtonRenderer")
                        videoId = overlay?.optJSONObject("playNavigationEndpoint")
                            ?.optJSONObject("watchEndpoint")
                            ?.optString("videoId", "") ?: ""
                    }

                    if (videoId.isEmpty() || videoId.length < 11) continue

                    val flexCols = item.optJSONArray("flexColumns") ?: continue
                    val col0 = flexCols.optJSONObject(0)
                        ?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")
                        ?.optJSONObject("text")?.optJSONArray("runs")
                    val title = col0?.optJSONObject(0)?.optString("text", "") ?: "Untitled"

                    val col1 = flexCols.optJSONObject(1)
                        ?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")
                        ?.optJSONObject("text")?.optJSONArray("runs")

                    var artist = "Unknown Artist"
                    var durationSec = 0
                    if (col1 != null && col1.length() > 0) {
                        artist = col1.optJSONObject(0)?.optString("text", artist) ?: artist
                        val lastRun = col1.optJSONObject(col1.length() - 1)?.optString("text", "") ?: ""
                        durationSec = parseDuration(lastRun)
                    }

                    var thumbnail = ""
                    val thumbs = item.optJSONObject("thumbnail")
                        ?.optJSONObject("musicThumbnailRenderer")
                        ?.optJSONObject("thumbnail")
                        ?.optJSONArray("thumbnails")
                    if (thumbs != null && thumbs.length() > 0) {
                        thumbnail = thumbs.optJSONObject(thumbs.length() - 1)?.optString("url", "") ?: ""
                    }
                    if (thumbnail.isEmpty()) {
                        thumbnail = "https://i.ytimg.com/vi/$videoId/hqdefault.jpg"
                    }

                    results.add(
                        TrackItem(
                            id = videoId,
                            title = title,
                            artist = artist,
                            duration = durationSec,
                            thumbnail = thumbnail,
                            url = "https://www.youtube.com/watch?v=$videoId",
                            platform = "youtube"
                        )
                    )
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return results
    }

    private fun searchDeezer(query: String, limit: Int): List<TrackItem> {
        val list = mutableListOf<TrackItem>()
        try {
            val encoded = URLEncoder.encode(query, "UTF-8")
            val url = URL("$DEEZER_URL?q=$encoded&limit=$limit")
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 6000
            conn.readTimeout = 6000
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")

            if (conn.responseCode == 200) {
                val res = conn.inputStream.bufferedReader().use { it.readText() }
                val root = JSONObject(res)
                val data = root.optJSONArray("data") ?: return emptyList()

                for (i in 0 until data.length()) {
                    val obj = data.optJSONObject(i) ?: continue
                    val id = obj.optLong("id").toString()
                    val title = obj.optString("title", "Untitled")
                    val artist = obj.optJSONObject("artist")?.optString("name", "Unknown Artist") ?: "Unknown"
                    val duration = obj.optInt("duration", 0)
                    val preview = obj.optString("preview", "")
                    val album = obj.optJSONObject("album")
                    val thumb = album?.optString("cover_medium") ?: album?.optString("cover_small") ?: ""

                    list.add(
                        TrackItem(
                            id = "dz_$id",
                            title = title,
                            artist = artist,
                            duration = duration,
                            thumbnail = thumb,
                            url = preview.ifEmpty { "https://www.deezer.com/track/$id" },
                            platform = "deezer"
                        )
                    )
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return list
    }

    private fun parseDuration(str: String): Int {
        val parts = str.trim().split(":")
        var sec = 0
        for (p in parts) {
            sec = sec * 60 + (p.toIntOrNull() ?: 0)
        }
        return sec
    }

    fun extractYouTubeId(raw: String): String {
        if (raw.isEmpty()) return ""
        if (raw.length == 11 && !raw.contains("/") && !raw.contains("?") && !raw.contains("=")) {
            return raw
        }
        val match = Regex("""(?:v=|/|vi/|youtu\.be/|embed/)([0-9A-Za-z_-]{11})""").find(raw)
        if (match != null) {
            return match.groupValues[1]
        }
        val clean = raw.replace(Regex("""^(yt_|youtube_|dz_)"""), "")
        if (clean.length == 11) return clean
        return ""
    }

    // Resolves audio stream URL with direct playback support for Android WebView
    fun resolveStreamUrl(videoId: String, title: String = "", artist: String = ""): JSONObject {
        val ytId = extractYouTubeId(videoId)

        // 1. Direct YouTube player API with ANDROID_VR client
        if (ytId.isNotEmpty()) {
            val directYtUrl = resolveYouTubeDirect(ytId)
            if (directYtUrl.isNotEmpty()) {
                return JSONObject().apply {
                    put("success", true)
                    put("isLocal", false)
                    put("streamUrl", directYtUrl)
                    put("isHls", false)
                }
            }
        }

        // 2. If it's a Deezer track preview
        if (videoId.startsWith("dz_")) {
            val cleanId = videoId.replace("dz_", "")
            val previewUrl = getDeezerTrackStream(cleanId)
            if (previewUrl.isNotEmpty()) {
                return JSONObject().apply {
                    put("success", true)
                    put("isLocal", false)
                    put("streamUrl", previewUrl)
                    put("isHls", false)
                }
            }
        }

        // 3. Fast Deezer Fallback (works instantly without login or cipher)
        val cleanTitle = title.replace(Regex("""\s*[\(\[].*?(feat|with|version|from).*?[\)\]]""", RegexOption.IGNORE_CASE), "").trim()
        val searchQuery = if (artist.isNotEmpty() && cleanTitle.isNotEmpty()) "$artist $cleanTitle" else (title.ifEmpty { artist })
        if (searchQuery.isNotEmpty()) {
            val deezerStream = resolveDeezerBySearch(searchQuery)
            if (deezerStream.isNotEmpty()) {
                return JSONObject().apply {
                    put("success", true)
                    put("isLocal", false)
                    put("streamUrl", deezerStream)
                    put("isHls", false)
                }
            }

            // 4. JioSaavn Fallback (universal high-speed direct audio)
            val saavnStream = resolveJioSaavn(searchQuery)
            if (saavnStream.isNotEmpty()) {
                return JSONObject().apply {
                    put("success", true)
                    put("isLocal", false)
                    put("streamUrl", saavnStream)
                    put("isHls", false)
                }
            }

            // 5. SoundCloud Fallback
            val scStream = resolveSoundCloudStream(searchQuery)
            if (scStream.isNotEmpty()) {
                val isHls = scStream.contains(".m3u8")
                return JSONObject().apply {
                    put("success", true)
                    put("isLocal", false)
                    put("streamUrl", scStream)
                    put("isHls", isHls)
                }
            }

            if (cleanTitle != title) {
                val rawStream = resolveDeezerBySearch("$artist $title".trim())
                if (rawStream.isNotEmpty()) {
                    return JSONObject().apply {
                        put("success", true)
                        put("isLocal", false)
                        put("streamUrl", rawStream)
                        put("isHls", false)
                    }
                }

                val rawSaavn = resolveJioSaavn("$artist $title".trim())
                if (rawSaavn.isNotEmpty()) {
                    return JSONObject().apply {
                        put("success", true)
                        put("isLocal", false)
                        put("streamUrl", rawSaavn)
                        put("isHls", false)
                    }
                }
            }
        }

        // 5. Fallback: Search YouTube Music
        if (title.isNotEmpty() || artist.isNotEmpty()) {
            val q = "$artist $title".trim()
            val ytTracks = searchYouTubeMusic(q, 2)
            for (t in ytTracks) {
                val tid = extractYouTubeId(t.id)
                if (tid.isNotEmpty() && tid != ytId) {
                    val stream = resolveYouTubeDirect(tid)
                    if (stream.isNotEmpty()) {
                        return JSONObject().apply {
                            put("success", true)
                            put("isLocal", false)
                            put("streamUrl", stream)
                            put("isHls", false)
                        }
                    }
                }
            }
        }

        return JSONObject().apply {
            put("success", false)
            put("error", "could not extract audio stream")
        }
    }

    private fun resolveYouTubeDirect(videoId: String): String {
        try {
            val url = URL(YT_PLAYER_URL)
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = 6000
            conn.readTimeout = 6000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("User-Agent", "Mozilla/5.0")

            val payload = JSONObject().apply {
                val context = JSONObject().apply {
                    val client = JSONObject().apply {
                        put("clientName", "ANDROID_VR")
                        put("clientVersion", "1.61.48")
                        put("deviceMake", "Oculus")
                        put("deviceModel", "Quest 3")
                        put("hl", "en")
                        put("gl", "US")
                    }
                    put("client", client)
                }
                put("context", context)
                put("videoId", videoId)
            }

            OutputStreamWriter(conn.outputStream, "UTF-8").use {
                it.write(payload.toString())
                it.flush()
            }

            if (conn.responseCode == 200) {
                val text = conn.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(text)
                val status = json.optJSONObject("playabilityStatus")?.optString("status", "")
                if (status == "OK") {
                    val formats = json.optJSONObject("streamingData")?.optJSONArray("adaptiveFormats")
                    if (formats != null) {
                        var fallbackAudioUrl = ""
                        for (i in 0 until formats.length()) {
                            val f = formats.optJSONObject(i) ?: continue
                            val mime = f.optString("mimeType", "")
                            if (mime.contains("audio/")) {
                                val streamUrl = f.optString("url", "")
                                if (streamUrl.isNotEmpty()) {
                                    val itag = f.optInt("itag", 0)
                                    if (itag == 140) { // 128k m4a
                                        return streamUrl
                                    }
                                    if (fallbackAudioUrl.isEmpty()) {
                                        fallbackAudioUrl = streamUrl
                                    }
                                }
                            }
                        }
                        if (fallbackAudioUrl.isNotEmpty()) {
                            return fallbackAudioUrl
                        }
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return ""
    }

    private fun resolveDeezerBySearch(query: String): String {
        try {
            val encoded = URLEncoder.encode(query, "UTF-8")
            val url = URL("$DEEZER_URL?q=$encoded&limit=3")
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 4000
            conn.readTimeout = 4000
            conn.setRequestProperty("User-Agent", "Mozilla/5.0")
            if (conn.responseCode == 200) {
                val res = conn.inputStream.bufferedReader().use { it.readText() }
                val root = JSONObject(res)
                val data = root.optJSONArray("data") ?: return ""
                for (i in 0 until data.length()) {
                    val obj = data.optJSONObject(i) ?: continue
                    val preview = obj.optString("preview", "")
                    if (preview.isNotEmpty() && preview.startsWith("http")) {
                        return preview
                    }
                }
            }
        } catch (_: Exception) {}
        return ""
    }

    private fun getDeezerTrackStream(id: String): String {
        try {
            val url = URL("https://api.deezer.com/track/$id")
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            conn.setRequestProperty("User-Agent", "Mozilla/5.0")
            if (conn.responseCode == 200) {
                val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                return json.optString("preview", "")
            }
        } catch (_: Exception) {}
        return ""
    }

    private fun resolveJioSaavn(query: String): String {
        if (query.isEmpty()) return ""
        try {
            val enc = URLEncoder.encode(query, "UTF-8")
            val searchUrl = URL("https://www.jiosaavn.com/api.php?__call=autocomplete.get&_format=json&_marker=0&cc=in&includeMetaTags=1&query=$enc")
            val conn = searchUrl.openConnection() as HttpURLConnection
            conn.connectTimeout = 4000
            conn.readTimeout = 4000
            conn.setRequestProperty("User-Agent", "Mozilla/5.0")
            if (conn.responseCode == 200) {
                val text = conn.inputStream.bufferedReader().use { it.readText() }
                val root = JSONObject(text)
                val songs = root.optJSONObject("songs")?.optJSONArray("data") ?: return ""
                if (songs.length() > 0) {
                    val first = songs.getJSONObject(0)
                    val pid = first.optString("id", "")
                    if (pid.isNotEmpty()) {
                        val detailUrl = URL("https://www.jiosaavn.com/api.php?__call=song.getDetails&cc=in&_marker=0&_format=json&pids=$pid")
                        val dConn = detailUrl.openConnection() as HttpURLConnection
                        dConn.connectTimeout = 4000
                        dConn.readTimeout = 4000
                        dConn.setRequestProperty("User-Agent", "Mozilla/5.0")
                        if (dConn.responseCode == 200) {
                            val dText = dConn.inputStream.bufferedReader().use { it.readText() }
                            val dRoot = JSONObject(dText)
                            val songObj = dRoot.optJSONObject(pid) ?: return ""
                            val preview = songObj.optString("media_preview_url", "")
                            if (preview.isNotEmpty() && preview.startsWith("http")) {
                                return preview
                            }
                        }
                    }
                }
            }
        } catch (_: Exception) {}
        return ""
    }

    private fun resolveSoundCloudStream(query: String): String {
        if (query.isEmpty()) return ""
        try {
            val enc = URLEncoder.encode(query, "UTF-8")
            val url = URL("https://api-v2.soundcloud.com/search/tracks?q=$enc&client_id=$SC_CLIENT_ID&limit=3")
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            conn.setRequestProperty("User-Agent", "Mozilla/5.0")
            if (conn.responseCode == 200) {
                val data = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                val tracks = data.optJSONArray("collection") ?: return ""
                for (tIdx in 0 until tracks.length()) {
                    val track = tracks.getJSONObject(tIdx)
                    val trans = track.optJSONObject("media")?.optJSONArray("transcodings") ?: continue
                    for (i in 0 until trans.length()) {
                        val tr = trans.getJSONObject(i)
                        val proto = tr.optJSONObject("format")?.optString("protocol", "") ?: ""
                        if (proto.contains("cbc-encrypted")) continue
                        val trUrl = tr.optString("url", "")
                        if (trUrl.isNotEmpty()) {
                            val stConn = URL("$trUrl?client_id=$SC_CLIENT_ID").openConnection() as HttpURLConnection
                            stConn.connectTimeout = 4000
                            stConn.readTimeout = 4000
                            stConn.setRequestProperty("User-Agent", "Mozilla/5.0")
                            if (stConn.responseCode == 200) {
                                val stData = JSONObject(stConn.inputStream.bufferedReader().use { it.readText() })
                                val finalUrl = stData.optString("url", "")
                                if (finalUrl.isNotEmpty()) {
                                    return finalUrl
                                }
                            }
                        }
                    }
                }
            }
        } catch (_: Exception) {}
        return ""
    }

    private fun wrapProxy(rawUrl: String): String {
        return "http://127.0.0.1:8888/api/audio-proxy?url=" + URLEncoder.encode(rawUrl, "UTF-8")
    }

    // Synchronized and plain lyrics from LRCLIB
    fun getLyrics(title: String, artist: String): JSONObject {
        val result = JSONObject()
        try {
            val encTitle = URLEncoder.encode(title, "UTF-8")
            val encArtist = URLEncoder.encode(artist, "UTF-8")
            val url = URL("$LRCLIB_URL?track_name=$encTitle&artist_name=$encArtist")
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 6000
            conn.readTimeout = 6000
            conn.setRequestProperty("User-Agent", "RiffAndroid/42.3.0")

            if (conn.responseCode == 200) {
                val response = conn.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(response)
                result.put("plain", json.optString("plainLyrics", ""))
                result.put("synced", json.optString("syncedLyrics", ""))
                result.put("source", "lrclib")
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return result
    }

    fun getArtist(name: String): JSONObject {
        val result = JSONObject()
        try {
            val encoded = URLEncoder.encode(name, "UTF-8")
            val url = URL("https://api.deezer.com/search/artist?q=$encoded&limit=1")
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            conn.setRequestProperty("User-Agent", "Mozilla/5.0")

            if (conn.responseCode == 200) {
                val response = conn.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(response)
                val data = json.optJSONArray("data")
                if (data != null && data.length() > 0) {
                    val a = data.getJSONObject(0)
                    result.put("name", a.optString("name", name))
                    result.put("picture", a.optString("picture_xl", a.optString("picture_medium", "")))
                    result.put("nb_fan", a.optInt("nb_fan", 0))
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return result
    }
}
