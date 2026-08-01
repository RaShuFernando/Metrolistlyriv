package com.metrolist.music.lyrics.vault

import android.os.Environment
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object FileLogger {

    private fun getLogFile(): File {
        val extStorage = Environment.getExternalStorageDirectory()
        val songSyncDir = File(extStorage, "SongSync")
        if (!songSyncDir.exists()) {
            songSyncDir.mkdirs()
        }
        return File(songSyncDir, "debug_log.txt")
    }

    @Synchronized
    fun log(module: String, message: String, throwable: Throwable? = null) {
        try {
            val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
            val logMessage = StringBuilder()
                .append("[$timestamp] [$module] $message")

            if (throwable != null) {
                logMessage.append("\nException: ").append(throwable.localizedMessage ?: throwable.toString())
                logMessage.append("\n").append(throwable.stackTraceToString())
            }
            logMessage.append("\n")

            getLogFile().appendText(logMessage.toString())
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun e(module: String, message: String, throwable: Throwable? = null) {
        log(module, message, throwable)
    }

    fun d(module: String, message: String) {
        log(module, message)
    }
}
