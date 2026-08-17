package com.metrolist.music.lyrics.vault

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.WatchEndpoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.regex.Pattern

/**
 * IsolatedShareReceiver is a standalone, append-only Activity that intercepts shared
 * YouTube/YouTube Music URLs, extracts the video ID, fetches metadata & lyrics via
 * the existing YouTube InnerTube engine without modifying any core classes,
 * and saves the result to the vault database.
 */
class IsolatedShareReceiver : Activity() {

    companion object {
        private const val TAG = "IsolatedShareReceiver"
        private val receiverScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        // Matches standard YouTube & YouTube Music URLs (youtube.com and youtu.be)
        private val YOUTUBE_ID_PATTERN = Pattern.compile(
            """(?:https?:\/\/)?(?:[a-zA-Z0-9_-]+\.)?(?:youtube\.com\/(?:watch\?.*v=|embed\/|v\/|shorts\/|live\/)|youtu\.be\/)([a-zA-Z0-9_-]{11})""",
            Pattern.CASE_INSENSITIVE
        )

        /**
         * Safely extracts an 11-character YouTube video ID from incoming text.
         */
        fun extractVideoId(text: String?): String? {
            if (text.isNullOrBlank()) return null

            val matcher = YOUTUBE_ID_PATTERN.matcher(text)
            if (matcher.find()) {
                return matcher.group(1)
            }

            return try {
                val trimmed = text.trim()
                val uri = Uri.parse(trimmed)
                when {
                    uri.host?.contains("youtu.be", ignoreCase = true) == true -> {
                        uri.lastPathSegment?.takeIf { it.length == 11 }
                    }
                    uri.host?.contains("youtube.com", ignoreCase = true) == true -> {
                        uri.getQueryParameter("v")?.takeIf { it.length == 11 }
                    }
                    trimmed.length == 11 && trimmed.matches(Regex("^[a-zA-Z0-9_-]{11}$")) -> {
                        trimmed
                    }
                    else -> null
                }
            } catch (_: Exception) {
                null
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        overridePendingTransition(0, 0)
        handleIntent(intent)
        finish()
        overridePendingTransition(0, 0)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        handleIntent(intent)
        finish()
        overridePendingTransition(0, 0)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent == null || intent.action != Intent.ACTION_SEND || intent.type != "text/plain") {
            return
        }

        val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)
        val videoId = extractVideoId(sharedText)
        val appContext = applicationContext

        if (videoId.isNullOrBlank()) {
            showToast(appContext, "Invalid or unsupported YouTube link")
            return
        }

        receiverScope.launch {
            processSharedVideo(appContext, videoId)
        }
    }

    private suspend fun processSharedVideo(context: Context, videoId: String) {
        withContext(Dispatchers.Main) {
            Toast.makeText(context, "Fetching song details…", Toast.LENGTH_SHORT).show()
        }

        try {
            // 1. Fetch metadata using the existing YouTube engine without modifying it
            val nextResult = YouTube.next(WatchEndpoint(videoId = videoId)).getOrThrow()
            val songItem = nextResult.items.firstOrNull { it.id == videoId }
                ?: nextResult.items.firstOrNull()

            val title = songItem?.title ?: nextResult.title ?: "Unknown Title"
            val artist = songItem?.artists?.joinToString(", ") { it.name } ?: "Unknown Artist"
            val album = songItem?.album?.name ?: ""
            val duration = songItem?.duration ?: 0
            val thumbnail = songItem?.thumbnail ?: ""

            // 2. Persist to vault database & fetch/parse SSE lyrics
            val vaultMetadata = VaultMetadata(
                videoId = videoId,
                title = title,
                artist = artist,
                album = album,
                durationSeconds = duration,
                albumArtUrl = thumbnail,
                composer = "",
                description = null,
                mediaId = videoId,
                artistBrowseId = songItem?.artists?.firstOrNull()?.id,
                albumBrowseId = songItem?.album?.id
            )

            LyricsSyncManager.syncLyrics(context, vaultMetadata)

            withContext(Dispatchers.Main) {
                Toast.makeText(context, "Lyrics saved for \"$title\"", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            FileLogger.e(TAG, "Failed to download lyrics for videoId $videoId", e)
            withContext(Dispatchers.Main) {
                Toast.makeText(context, "Failed to download lyrics: ${e.localizedMessage ?: "Unknown error"}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showToast(context: Context, message: String) {
        receiverScope.launch(Dispatchers.Main) {
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }
}
