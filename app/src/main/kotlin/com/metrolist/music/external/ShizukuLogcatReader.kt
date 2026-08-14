package com.metrolist.music.external

import android.content.pm.PackageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import java.io.BufferedReader
import java.io.InputStreamReader

object ShizukuLogcatReader {

    private var lastExtractedVideoId: String? = null
    private const val SHIZUKU_REQ_CODE = 1001

    suspend fun extractVideoId(): String? = withContext(Dispatchers.IO) {
        if (!Shizuku.pingBinder()) {
            return@withContext null
        }
        
        if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
            Shizuku.requestPermission(SHIZUKU_REQ_CODE)
            return@withContext null
        }

        val maxAttempts = 6 // 500ms * 6 = 3 seconds
        var attempt = 0

        while (attempt < maxAttempts) {
            val videoId = readLogcat()
            if (videoId != null && videoId != lastExtractedVideoId) {
                lastExtractedVideoId = videoId
                clearLogcat()
                return@withContext videoId
            }
            delay(500)
            attempt++
        }
        null
    }

    private fun createProcess(command: Array<String>): Process {
        return try {
            // Find the newProcess method dynamically to bypass visibility restrictions
            val newProcessMethod = rikka.shizuku.Shizuku::class.java.getDeclaredMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java
            )
            
            // Ensure it's accessible even if marked private
            newProcessMethod.isAccessible = true
            
            // Invoke it and cast strictly to the standard java.lang.Process interface
            newProcessMethod.invoke(null, command, null, null) as Process
        } catch (e: Exception) {
            e.printStackTrace()
            // Fallback to standard runtime execution if Shizuku reflection fails
            // Note: This fallback will not have ADB privileges, but prevents a hard crash
            Runtime.getRuntime().exec(command)
        }
    }

    private suspend fun readLogcat(): String? = withContext(Dispatchers.IO) {
        return@withContext try {
            val command = arrayOf("logcat", "-d", "-s", "YTMusicVideoProbe")
            val process = createProcess(command)
            
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            var line: String?
            var foundVideoId: String? = null

            while (reader.readLine().also { line = it } != null) {
                val match = Regex("videoId=\\[?([a-zA-Z0-9_-]{11})\\]?").find(line ?: "")
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

    private suspend fun clearLogcat() = withContext(Dispatchers.IO) {
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
