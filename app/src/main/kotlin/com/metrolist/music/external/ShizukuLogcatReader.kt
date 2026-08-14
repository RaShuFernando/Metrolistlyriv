package com.metrolist.music.external

import kotlinx.coroutines.delay
import rikka.shizuku.Shizuku
import java.io.BufferedReader
import java.io.InputStreamReader

object ShizukuLogcatReader {

    private var lastExtractedVideoId: String? = null

    suspend fun extractVideoId(): String? {
        if (!Shizuku.pingBinder()) {
            return null
        }

        val maxAttempts = 6 // 500ms * 6 = 3 seconds
        var attempt = 0

        while (attempt < maxAttempts) {
            val videoId = readLogcat()
            if (videoId != null && videoId != lastExtractedVideoId) {
                lastExtractedVideoId = videoId
                clearLogcat()
                return videoId
            }
            delay(500)
            attempt++
        }
        return null
    }

    private fun readLogcat(): String? {
        return try {
            val process: java.lang.Process = Shizuku.newProcess(arrayOf("logcat", "-d", "-s", "YTMusicVideoProbe"), null, null)
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            var line: String?
            var foundVideoId: String? = null

            while (reader.readLine().also { line = it } != null) {
                val match = Regex("videoId=\\[(.*?)\\]").find(line ?: "")
                if (match != null) {
                    foundVideoId = match.groupValues[1]
                }
            }
            process.waitFor()
            foundVideoId
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun clearLogcat() {
        try {
            val process: java.lang.Process = Shizuku.newProcess(arrayOf("logcat", "-c"), null, null)
            process.waitFor()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
