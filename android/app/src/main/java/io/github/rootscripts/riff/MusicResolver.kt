package io.github.rootscripts.riff

import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
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
    private const val LRCLIB_URL = "https://lrclib.net/api/get"
    private const val DEEZER_URL = "https://api.deezer.com/search"

    // Search YouTube Music using the official Web Remix client context
    fun searchYouTubeMusic(query: String, limit: Int = 14): List<TrackItem> {
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
            conn.setRequestProperty("User-Agent", "Mozilla/5.0")

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

    // Resolves audio stream URL
    fun resolveStreamUrl(videoId: String, title: String = "", artist: String = ""): JSONObject {
        val response = JSONObject()
        val cleanId = videoId.replace(Regex("^(yt_|youtube_|dz_)"), "")

        // 1. If it's a Deezer track preview
        if (videoId.startsWith("dz_")) {
            val previewUrl = getDeezerTrackStream(cleanId)
            if (previewUrl.isNotEmpty()) {
                response.put("success", true)
                response.put("isLocal", false)
                response.put("streamUrl", previewUrl)
                response.put("isHls", false)
                return response
            }
        }

        // 2. Query public fast Invidious / Piped stream API
        val pipedInstances = listOf(
            "https://pipedapi.kavin.rocks",
            "https://api.piped.private.coffee",
            "https://piped-api.lunar.icu",
            "https://pa.il.ax"
        )

        for (host in pipedInstances) {
            try {
                val url = URL("$host/streams/$cleanId")
                val conn = url.openConnection() as HttpURLConnection
                conn.connectTimeout = 4000
                conn.readTimeout = 4000
                conn.setRequestProperty("User-Agent", "Mozilla/5.0")

                if (conn.responseCode == 200) {
                    val text = conn.inputStream.bufferedReader().use { it.readText() }
                    val json = JSONObject(text)
                    val audioStreams = json.optJSONArray("audioStreams")
                    if (audioStreams != null && audioStreams.length() > 0) {
                        val stream = audioStreams.getJSONObject(0).optString("url", "")
                        if (stream.isNotEmpty()) {
                            response.put("success", true)
                            response.put("isLocal", false)
                            response.put("streamUrl", stream)
                            response.put("isHls", stream.contains(".m3u8"))
                            return response
                        }
                    }
                }
            } catch (_: Exception) {}
        }

        // 3. Fallback: Search track on Deezer for instant preview streaming
        if (title.isNotEmpty() || artist.isNotEmpty()) {
            val q = "$artist $title".trim()
            val fallbackTracks = searchDeezer(q, 1)
            if (fallbackTracks.isNotEmpty() && fallbackTracks[0].url.startsWith("http")) {
                response.put("success", true)
                response.put("isLocal", false)
                response.put("streamUrl", fallbackTracks[0].url)
                response.put("isHls", false)
                return response
            }
        }

        response.put("success", false)
        response.put("error", "Stream not available")
        return response
    }

    private fun getDeezerTrackStream(id: String): String {
        try {
            val url = URL("https://api.deezer.com/track/$id")
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            if (conn.responseCode == 200) {
                val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                return json.optString("preview", "")
            }
        } catch (_: Exception) {}
        return ""
    }

    // Synchronized and plain lyrics from LRCLIB
    fun getLyrics(title: String, artist: String): JSONObject {
        val result = JSONObject()
        try {
            val encTitle = URLEncoder.encode(title, "UTF-8")
            val encArtist = URLEncoder.encode(artist, "UTF-8")
            val url = URL("$LRCLIB_URL?track_name=$encTitle&artist_name=$encArtist")
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            conn.setRequestProperty("User-Agent", "Riff-Android/42.3.0 (https://github.com/rootscripts/riff)")

            if (conn.responseCode == 200) {
                val text = conn.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(text)
                val syncedLyrics = json.optString("syncedLyrics", "")
                val plainLyrics = json.optString("plainLyrics", "")

                if (syncedLyrics.isNotEmpty()) {
                    result.put("success", true)
                    result.put("synced", true)
                    result.put("plain", plainLyrics)
                    result.put("raw", syncedLyrics)
                    return result
                } else if (plainLyrics.isNotEmpty()) {
                    result.put("success", true)
                    result.put("synced", false)
                    result.put("plain", plainLyrics)
                    return result
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        result.put("success", false)
        result.put("error", "No lyrics found")
        return result
    }

    // Artist details and discography
    fun getArtist(name: String): JSONObject {
        val result = JSONObject()
        try {
            val enc = URLEncoder.encode(name, "UTF-8")
            val url = URL("$DEEZER_URL?q=artist:\"$enc\"&limit=10")
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 6000
            conn.readTimeout = 6000
            if (conn.responseCode == 200) {
                val res = conn.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(res)
                val data = json.optJSONArray("data")
                if (data != null && data.length() > 0) {
                    val first = data.getJSONObject(0)
                    val artistObj = first.optJSONObject("artist")
                    result.put("name", artistObj?.optString("name", name) ?: name)
                    result.put("picture", artistObj?.optString("picture_medium", "") ?: "")
                    result.put("tracks", data)
                    result.put("success", true)
                    return result
                }
            }
        } catch (_: Exception) {}
        result.put("success", false)
        return result
    }
}
