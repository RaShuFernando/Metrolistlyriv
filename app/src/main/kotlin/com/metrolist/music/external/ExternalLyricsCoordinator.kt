package com.metrolist.music.external

import android.content.Context
import com.metrolist.music.lyrics.vault.LyricsSyncManager
import com.metrolist.music.lyrics.vault.VaultMetadata
import com.metrolist.music.standaloneinnertube.StandaloneInnerTubeClient

object ExternalLyricsCoordinator {

    var currentVideoId: String? = null
        private set

    suspend fun onNewTrackDetected(context: Context) {
        val videoId = ShizukuLogcatReader.extractVideoId()
        if (videoId != null) {
            currentVideoId = videoId
            val metadata = StandaloneInnerTubeClient.fetchMetadata(videoId)
            if (metadata != null) {
                val vaultMetadata = VaultMetadata(
                    videoId = videoId,
                    title = metadata.title,
                    artist = metadata.artist,
                    album = metadata.album,
                    durationSeconds = metadata.durationSeconds,
                    albumArtUrl = metadata.albumArtUrl,
                    composer = metadata.artist,
                    mediaId = videoId
                )
                LyricsSyncManager.syncLyrics(context, vaultMetadata)
            }
        }
    }
}
