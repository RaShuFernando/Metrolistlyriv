package com.metrolist.music.lyrics.vault

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream

class RateLimitException(message: String) : Exception(message)

object BetterLyricsDownloader {
    
    private val client = OkHttpClient.Builder()
        .build()

    suspend fun downloadSseStream(
        context: Context,
        videoId: String,
        songTitle: String,
        artistName: String,
        albumName: String,
        durationSeconds: Int,
        description: String = "",
        jwtToken: String
    ): File? = withContext(Dispatchers.IO) {
        try {
            val formBodyBuilder = FormBody.Builder()
                .add("videoId", videoId)
                .add("song", songTitle)
                .add("artist", artistName)
                .add("album", albumName)
                .add("duration", durationSeconds.toString())
                .add("alwaysFetchMetadata", "false")
                .add("token", jwtToken)

            if (description.isNotEmpty()) {
                formBodyBuilder.add("description", description)
            }

            val formBody = formBodyBuilder.build()

            val request = Request.Builder()
                .url("https://lyrics.api.dacubeking.com/v2/lyrics")
                .post(formBody)
                .addHeader("Origin", "https://music.youtube.com")
                .addHeader("Referer", "https://music.youtube.com/")
                .addHeader("Content-Type", "application/x-www-form-urlencoded")
                .build()

            client.newCall(request).execute().use { response ->
                if (response.code == 429) {
                    throw RateLimitException("HTTP 429 Too Many Requests")
                }
                if (!response.isSuccessful) return@use null

                val body = response.body ?: return@use null
                val tempFile = File.createTempFile("lyrics_stream_", ".sse", context.cacheDir)
                
                body.byteStream().use { input ->
                    FileOutputStream(tempFile).use { output ->
                        input.copyTo(output)
                    }
                }
                
                tempFile
            }
        } catch (e: Exception) {
            FileLogger.e("BetterLyricsDownloader", "Failed to download SSE stream for $songTitle ($videoId)", e)
            null
        }
    }
}
