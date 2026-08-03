package com.metrolist.music.lyrics.vault

import android.content.Context
import androidx.media3.common.Timeline
import com.metrolist.music.db.MusicDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import timber.log.Timber

import com.metrolist.music.extensions.metadata

object LyricsPrefetchManager {
    private const val TAG = "LyricsPrefetchManager"
    private var currentPrefetchJob: Job? = null

    fun prefetchUpcomingLyrics(
        context: Context,
        scope: CoroutineScope,
        database: MusicDatabase,
        queueWindows: List<Timeline.Window>,
        currentIndex: Int,
        prefetchCount: Int
    ) {
        // Cancel any ongoing prefetch when the track changes
        currentPrefetchJob?.cancel()

        if (currentIndex < 0 || prefetchCount <= 0) return

        val upcomingWindows = queueWindows.drop(currentIndex + 1).take(prefetchCount)
        if (upcomingWindows.isEmpty()) return

        currentPrefetchJob = scope.launch(Dispatchers.IO) {
            Timber.tag(TAG).d("Starting prefetch for ${upcomingWindows.size} upcoming songs")

            for (window in upcomingWindows) {
                // Fixed mediaItem.metadata to mediaItem.mediaMetadata as requested
                val mediaMetadata = window.mediaItem.mediaMetadata
                
                // Using the custom extension for extended properties like duration and explicit
                val customMetadata = window.mediaItem.metadata ?: continue
                
                // Fixed mediaItem.id to mediaItem.mediaId
                val videoId = window.mediaItem.mediaId ?: continue

                // Check local DB first to see if lyrics exist
                val existing = database.lyrics(videoId).first()
                if (existing != null) {
                    Timber.tag(TAG).d("Lyrics already exist for $videoId, skipping prefetch.")
                    continue
                }

                val durationSeconds = if (customMetadata.duration.toInt() > 0) customMetadata.duration.toInt() else -1
                if (durationSeconds <= 0) continue

                val vaultMetadata = VaultMetadata(
                    videoId = window.mediaItem.mediaId ?: "", // Fix: Use mediaItem.mediaId
                    title = mediaMetadata.title?.toString() ?: customMetadata.title,
                    // Fix: Explicitly named the lambda variable to 'artist' instead of implicit 'it'
                    artist = customMetadata.artists.joinToString(", ") { artist -> artist.name },
                    album = mediaMetadata.albumTitle?.toString() ?: customMetadata.album?.title ?: "",
                    durationSeconds = durationSeconds,
                    albumArtUrl = mediaMetadata.artworkUri?.toString() ?: customMetadata.thumbnailUrl ?: "",
                    composer = "",
                    description = "",
                    mediaId = window.mediaItem.mediaId ?: "", // Fix: Use mediaItem.mediaId
                    releaseYear = null,
                    trackNumber = null,
                    artistBrowseId = customMetadata.artists.firstOrNull()?.id,
                    albumBrowseId = customMetadata.album?.id,
                    isExplicit = customMetadata.explicit
                )

                try {
                    LyricsSyncManager.syncLyrics(context.applicationContext, vaultMetadata)
                } catch (e: RateLimitException) {
                    Timber.tag(TAG).w("Rate limit (HTTP 429) hit during prefetch. Aborting entire prefetch batch.")
                    break // Abort the loop
                } catch (e: Exception) {
                    Timber.tag(TAG).e(e, "Error pre-fetching lyrics for $videoId")
                }

                // Mandatory delay to avoid HTTP 429
                delay(3000L)
            }
            Timber.tag(TAG).d("Prefetch batch completed or aborted.")
        }
    }
}
