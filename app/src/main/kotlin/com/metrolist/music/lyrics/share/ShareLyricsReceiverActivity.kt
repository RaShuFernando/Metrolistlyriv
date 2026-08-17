package com.metrolist.music.lyrics.share

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import com.metrolist.music.R
import com.metrolist.music.lyrics.vault.FileLogger
import com.metrolist.music.lyrics.vault.LyricsSyncManager
import com.metrolist.music.lyrics.vault.VaultMetadata
import com.metrolist.music.standaloneinnertube.StandaloneInnerTubeClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class ShareLyricsReceiverActivity : Activity() {

    companion object {
        private const val TAG = "ShareLyricsReceiver"
        private val receiverScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
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
        val videoId = YouTubeUrlParser.extractVideoId(sharedText)

        val appContext = applicationContext

        if (videoId.isNullOrBlank()) {
            showToast(appContext, appContext.getString(R.string.invalid_youtube_link))
            return
        }

        receiverScope.launch {
            processSharedVideo(appContext, videoId)
        }
    }

    private suspend fun processSharedVideo(context: Context, videoId: String) {
        try {
            showToast(context, context.getString(R.string.downloading_lyrics))

            val metadata = StandaloneInnerTubeClient.fetchMetadata(videoId)
            if (metadata == null) {
                FileLogger.e(TAG, "Failed to fetch metadata from InnerTube for videoId: $videoId")
                showToast(context, context.getString(R.string.lyrics_download_failed, videoId))
                return
            }

            val vaultMetadata = VaultMetadata(
                videoId = videoId,
                title = metadata.title,
                artist = metadata.artist,
                album = metadata.album,
                durationSeconds = metadata.durationSeconds,
                albumArtUrl = metadata.albumArtUrl,
                composer = "",
                description = null,
                mediaId = videoId
            )

            LyricsSyncManager.syncLyrics(context, vaultMetadata)
            showToast(context, context.getString(R.string.lyrics_downloaded_success, metadata.title))
        } catch (e: Exception) {
            FileLogger.e(TAG, "Exception while downloading lyrics for videoId $videoId", e)
            showToast(context, context.getString(R.string.lyrics_download_failed, videoId))
        }
    }

    private fun showToast(context: Context, message: String) {
        CoroutineScope(Dispatchers.Main).launch {
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }
}
