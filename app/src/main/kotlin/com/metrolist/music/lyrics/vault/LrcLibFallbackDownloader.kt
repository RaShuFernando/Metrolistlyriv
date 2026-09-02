package com.metrolist.music.lyrics.vault

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlin.math.abs

object LrcLibFallbackDownloader {

    private const val TAG = "LrcLibFallbackDownloader"
    private const val USER_AGENT = "Metrolistlyriv (https://github.com/RaShuFernando/Metrolistlyriv)"

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    suspend fun fetchLyrics(
        trackName: String,
        artistName: String,
        albumName: String,
        durationSeconds: Int
    ): ParsedLyrics? = withContext(Dispatchers.IO) {
        if (trackName.isBlank() || artistName.isBlank()) {
            return@withContext null
        }

        val rawTitle = trackName.trim()
        val rawArtist = artistName.trim()
        val cleanedTitle = cleanTrackTitle(rawTitle)
        val cleanedArtist = cleanArtistName(rawArtist)

        // 1. Strict /api/get (with exact album & duration if available)
        fetchViaGet(cleanedTitle, cleanedArtist, albumName.trim(), durationSeconds)?.let { return@withContext it }

        // 2. Relaxed /api/get (cleaned title and artist, no album or duration constraints)
        if (cleanedTitle != rawTitle || albumName.isNotBlank() || durationSeconds > 0) {
            fetchViaGet(cleanedTitle, cleanedArtist, "", 0)?.let { return@withContext it }
        }

        // 3. /api/search with track_name & artist_name
        fetchViaSearch(
            trackName = cleanedTitle,
            artistName = cleanedArtist,
            targetDurationSeconds = durationSeconds
        )?.let { return@withContext it }

        // 4. Fallback search query /api/search?q=...
        fetchViaGeneralSearch(
            query = "$cleanedTitle $cleanedArtist",
            targetDurationSeconds = durationSeconds
        )?.let { return@withContext it }

        // 5. If artist has multiple names, try with primary artist only
        val primaryArtist = cleanedArtist.split(",", "&", "feat.", "ft.", "/").firstOrNull()?.trim()
        if (!primaryArtist.isNullOrBlank() && primaryArtist != cleanedArtist) {
            fetchViaSearch(
                trackName = cleanedTitle,
                artistName = primaryArtist,
                targetDurationSeconds = durationSeconds
            )?.let { return@withContext it }
        }

        FileLogger.d(TAG, "No lyrics found from LRCLIB for: $trackName - $artistName")
        null
    }

    private fun fetchViaGet(
        trackName: String,
        artistName: String,
        albumName: String,
        durationSeconds: Int
    ): ParsedLyrics? {
        try {
            val urlBuilder = HttpUrl.Builder()
                .scheme("https")
                .host("lrclib.net")
                .addPathSegment("api")
                .addPathSegment("get")
                .addQueryParameter("track_name", trackName)
                .addQueryParameter("artist_name", artistName)

            if (albumName.isNotBlank()) {
                urlBuilder.addQueryParameter("album_name", albumName)
            }
            if (durationSeconds > 0) {
                urlBuilder.addQueryParameter("duration", durationSeconds.toString())
            }

            val request = Request.Builder()
                .url(urlBuilder.build())
                .addHeader("User-Agent", USER_AGENT)
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val bodyString = response.body.string()
                if (bodyString.isBlank()) return null

                val json = JSONObject(bodyString)
                return parseLyricJson(json)
            }
        } catch (e: Exception) {
            FileLogger.d(TAG, "LRCLIB /api/get request failed for $trackName - $artistName: ${e.message}")
            return null
        }
    }

    private fun fetchViaSearch(
        trackName: String,
        artistName: String,
        targetDurationSeconds: Int
    ): ParsedLyrics? {
        try {
            val urlBuilder = HttpUrl.Builder()
                .scheme("https")
                .host("lrclib.net")
                .addPathSegment("api")
                .addPathSegment("search")
                .addQueryParameter("track_name", trackName)
                .addQueryParameter("artist_name", artistName)

            val request = Request.Builder()
                .url(urlBuilder.build())
                .addHeader("User-Agent", USER_AGENT)
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val bodyString = response.body.string()
                if (bodyString.isBlank()) return null

                val jsonArray = JSONArray(bodyString)
                return selectBestSearchResult(jsonArray, targetDurationSeconds)
            }
        } catch (e: Exception) {
            FileLogger.d(TAG, "LRCLIB /api/search request failed for $trackName - $artistName: ${e.message}")
            return null
        }
    }

    private fun fetchViaGeneralSearch(
        query: String,
        targetDurationSeconds: Int
    ): ParsedLyrics? {
        try {
            val urlBuilder = HttpUrl.Builder()
                .scheme("https")
                .host("lrclib.net")
                .addPathSegment("api")
                .addPathSegment("search")
                .addQueryParameter("q", query)

            val request = Request.Builder()
                .url(urlBuilder.build())
                .addHeader("User-Agent", USER_AGENT)
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val bodyString = response.body.string()
                if (bodyString.isBlank()) return null

                val jsonArray = JSONArray(bodyString)
                return selectBestSearchResult(jsonArray, targetDurationSeconds)
            }
        } catch (e: Exception) {
            FileLogger.d(TAG, "LRCLIB general search failed for $query: ${e.message}")
            return null
        }
    }

    private fun selectBestSearchResult(jsonArray: JSONArray, targetDurationSeconds: Int): ParsedLyrics? {
        if (jsonArray.length() == 0) return null

        val candidates = mutableListOf<JSONObject>()
        for (i in 0 until jsonArray.length()) {
            val obj = jsonArray.optJSONObject(i) ?: continue
            candidates.add(obj)
        }

        if (candidates.isEmpty()) return null

        // Score candidates: synced lyrics with closest duration
        val withSynced = candidates.filter {
            !it.isNull("syncedLyrics") && it.optString("syncedLyrics").isNotBlank() && it.optString("syncedLyrics") != "null"
        }

        if (withSynced.isNotEmpty()) {
            if (targetDurationSeconds > 0) {
                val best = withSynced.minByOrNull { obj ->
                    val duration = obj.optInt("duration", 0)
                    if (duration > 0) abs(duration - targetDurationSeconds) else 9999
                }
                if (best != null) return parseLyricJson(best)
            }
            return parseLyricJson(withSynced.first())
        }

        // Fallback to plain lyrics
        val withPlain = candidates.filter {
            !it.isNull("plainLyrics") && it.optString("plainLyrics").isNotBlank() && it.optString("plainLyrics") != "null"
        }

        if (withPlain.isNotEmpty()) {
            if (targetDurationSeconds > 0) {
                val best = withPlain.minByOrNull { obj ->
                    val duration = obj.optInt("duration", 0)
                    if (duration > 0) abs(duration - targetDurationSeconds) else 9999
                }
                if (best != null) return parseLyricJson(best)
            }
            return parseLyricJson(withPlain.first())
        }

        return null
    }

    private fun parseLyricJson(json: JSONObject): ParsedLyrics? {
        val syncedLyrics = if (!json.isNull("syncedLyrics")) {
            json.optString("syncedLyrics").trim().takeIf { it.isNotEmpty() && it != "null" }
        } else null

        val plainLyrics = if (!json.isNull("plainLyrics")) {
            json.optString("plainLyrics").trim().takeIf { it.isNotEmpty() && it != "null" }
        } else null

        return when {
            syncedLyrics != null -> {
                ParsedLyrics(
                    provider = "lrclib",
                    type = "Line",
                    format = "lrc",
                    lyricsText = syncedLyrics,
                    rank = LyricsRanking.getRank("lrclib", "Line")
                )
            }
            plainLyrics != null -> {
                ParsedLyrics(
                    provider = "lrclib",
                    type = "Unsynced",
                    format = "txt",
                    lyricsText = plainLyrics,
                    rank = LyricsRanking.getRank("lrclib", "Unsynced")
                )
            }
            else -> null
        }
    }

    private fun cleanTrackTitle(title: String): String {
        return title.replace(Regex("""[\u200B-\u200D\uFEFF\u200E\u200F]"""), "")
            .replace(
                Regex("""(?i)\s*[\(\[](?:official\s*(?:video|audio|music\s*video|lyric\s*video|visualizer|hd|4k)?|feat\.?|ft\.?|remastered|explicit|audio|lyrics?).*?[\)\]]"""),
                ""
            )
            .replace(Regex("""\s+"""), " ")
            .trim()
    }

    private fun cleanArtistName(artist: String): String {
        return artist.replace(Regex("""[\u200B-\u200D\uFEFF\u200E\u200F]"""), "")
            .replace(Regex("""\s+"""), " ")
            .trim()
    }
}
