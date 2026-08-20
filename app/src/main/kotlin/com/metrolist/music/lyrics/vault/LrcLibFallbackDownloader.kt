package com.metrolist.music.lyrics.vault

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

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

        try {
            val urlBuilder = HttpUrl.Builder()
                .scheme("https")
                .host("lrclib.net")
                .addPathSegment("api")
                .addPathSegment("get")
                .addQueryParameter("track_name", trackName.trim())
                .addQueryParameter("artist_name", artistName.trim())

            if (albumName.isNotBlank()) {
                urlBuilder.addQueryParameter("album_name", albumName.trim())
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
                if (!response.isSuccessful) {
                    FileLogger.d(TAG, "LRCLIB API returned HTTP ${response.code} for $trackName - $artistName")
                    return@use null
                }

                val bodyString = response.body?.string() ?: return@use null
                if (bodyString.isBlank()) return@use null

                val json = JSONObject(bodyString)

                val syncedLyrics = if (!json.isNull("syncedLyrics")) {
                    json.optString("syncedLyrics").trim().takeIf { it.isNotEmpty() && it != "null" }
                } else null

                val plainLyrics = if (!json.isNull("plainLyrics")) {
                    json.optString("plainLyrics").trim().takeIf { it.isNotEmpty() && it != "null" }
                } else null

                when {
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
        } catch (e: Exception) {
            FileLogger.e(TAG, "Failed to fetch fallback lyrics from LRCLIB for $trackName - $artistName: ${e.message}", e)
            null
        }
    }
}
