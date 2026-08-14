package com.metrolist.music.standaloneinnertube

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

data class StandaloneInnerTubeMetadata(
    val title: String,
    val artist: String,
    val album: String,
    val durationSeconds: Int,
    val albumArtUrl: String
)

object StandaloneInnerTubeClient {
    private val client = OkHttpClient()
    private val JSON = "application/json; charset=utf-8".toMediaType()

    suspend fun fetchMetadata(videoId: String): StandaloneInnerTubeMetadata? = withContext(Dispatchers.IO) {
        try {
            val payload = JSONObject().apply {
                val context = JSONObject().apply {
                    val client = JSONObject().apply {
                        put("clientName", "WEB_REMIX")
                        put("clientVersion", "1.20230508.01.00")
                    }
                    put("client", client)
                }
                put("context", context)
                put("videoId", videoId)
            }

            val request = Request.Builder()
                .url("https://music.youtube.com/youtubei/v1/player")
                .post(payload.toString().toRequestBody(JSON))
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) return@withContext null

            val responseBody = response.body?.string() ?: return@withContext null
            val jsonResponse = JSONObject(responseBody)

            val videoDetails = jsonResponse.optJSONObject("videoDetails") ?: return@withContext null
            val title = videoDetails.optString("title")
            val artist = videoDetails.optString("author")
            val durationSeconds = videoDetails.optString("lengthSeconds").toIntOrNull() ?: 0

            val thumbnailArray = videoDetails.optJSONObject("thumbnail")?.optJSONArray("thumbnails")
            var albumArtUrl = ""
            if (thumbnailArray != null && thumbnailArray.length() > 0) {
                albumArtUrl = thumbnailArray.optJSONObject(thumbnailArray.length() - 1)?.optString("url") ?: ""
            }
            
            // The player endpoint doesn't strictly provide album name in videoDetails
            // Fallback to title as requested or simplified.
            val album = title 
            
            StandaloneInnerTubeMetadata(
                title = title,
                artist = artist,
                album = album,
                durationSeconds = durationSeconds,
                albumArtUrl = albumArtUrl
            )
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}
