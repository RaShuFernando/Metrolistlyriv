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

    private fun createProcess(command: Array<String>): java.lang.Process {
        val newProcessMethod = Shizuku::class.java.getMethod(
            "newProcess",
            Array<String>::class.java,
            Array<String>::class.java,
            String::class.java
        )
        return newProcessMethod.invoke(null, command, null, null) as java.lang.Process
    }

    private fun readLogcat(): String? {
        return try {
            val command = arrayOf("logcat", "-d", "-s", "YTMusicVideoProbe")
            val process = createProcess(command)
            
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
            process.destroy()
            foundVideoId
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun clearLogcat() {
        try {
            val command = arrayOf("logcat", "-c")
            val process = createProcess(command)
            process.waitFor()
            process.destroy()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
