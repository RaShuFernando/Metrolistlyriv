package com.metrolist.music.lyrics.overlay

import android.content.Context
import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.WatchEndpoint
import com.metrolist.innertube.models.YouTubeClient
import com.metrolist.music.lyrics.vault.DatabaseProvider
import com.metrolist.music.lyrics.vault.FileLogger
import com.metrolist.music.lyrics.vault.LyricsSyncManager
import com.metrolist.music.lyrics.vault.VaultMetadata
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * ExternalLyricsSyncManager coordinates metadata resolution and lyrics syncing
 * for external media playback (e.g. patched YouTube Music).
 *
 * Responsibilities:
 * 1. Checks local Vault database for existing cached lyrics for a given videoId.
 * 2. Fetches track metadata via InnerTube standard endpoints when missing.
 * 3. Triggers VaultManager / BetterLyricsDownloader pipeline to fetch and cache lyrics in the database.
 */
object ExternalLyricsSyncManager {

    private const val TAG = "ExternalLyricsSyncMgr"

    /**
     * Ensures lyrics are available in the local vault for the specified [videoId].
     * If not found locally, fetches metadata from InnerTube and triggers the download pipeline.
     */
    suspend fun ensureLyricsForVideo(context: Context, videoId: String): Boolean = withContext(Dispatchers.IO) {
        if (videoId.isBlank()) return@withContext false

        try {
            val masterPath = LyricsSyncManager.getMasterFolderPath()
            val db = DatabaseProvider.getDatabase(context, masterPath)
            val dao = db.libraryDao()

            // 1. Check local Vault database
            val existingIndex = dao.getSongIndexByVideoId(videoId)
            val existingSong = dao.getSongByVideoId(videoId)

            if (existingIndex != null && existingIndex.activeLyricFile.isNotBlank()) {
                FileLogger.d(TAG, "Lyrics index already exists in vault for videoId: $videoId")
                return@withContext true
            }
            if (existingSong != null && existingSong.hasLyricsAvailable) {
                FileLogger.d(TAG, "Song entity with lyrics already exists in vault for videoId: $videoId")
                return@withContext true
            }

            // 2. Fetch metadata via InnerTube
            FileLogger.d(TAG, "Fetching track metadata via InnerTube for videoId: $videoId")
            val nextResult = runCatching {
                YouTube.next(WatchEndpoint(videoId = videoId)).getOrNull()
            }.getOrNull()

            val songItem = nextResult?.items?.firstOrNull { it.id == videoId }
                ?: nextResult?.items?.firstOrNull()

            var title = songItem?.title ?: nextResult?.title ?: ""
            var artist = songItem?.artists?.joinToString(", ") { it.name } ?: ""
            var album = songItem?.album?.name ?: ""
            var durationSeconds = songItem?.duration ?: 0
            var thumbnail = songItem?.thumbnail ?: ""

            // Fallback to player endpoint if next didn't resolve title/artist
            if (title.isBlank() || artist.isBlank()) {
                val playerResult = runCatching {
                    YouTube.player(videoId = videoId, client = YouTubeClient.WEB_REMIX).getOrNull()
                }.getOrNull()

                val details = playerResult?.videoDetails
                if (details != null) {
                    if (title.isBlank()) title = details.title ?: ""
                    if (artist.isBlank()) artist = details.author ?: ""
                    if (durationSeconds <= 0) durationSeconds = details.lengthSeconds.toIntOrNull() ?: 0
                    if (thumbnail.isBlank()) {
                        thumbnail = details.thumbnail.thumbnails.lastOrNull()?.url ?: ""
                    }
                }
            }

            val finalTitle = title.ifBlank { "Unknown Track" }
            val finalArtist = artist.ifBlank { "Unknown Artist" }
            val finalAlbum = album.ifBlank { finalTitle }

            FileLogger.d(TAG, "Resolved metadata: \"$finalTitle\" by \"$finalArtist\" (album: \"$finalAlbum\", duration: ${durationSeconds}s)")

            // 3. Construct VaultMetadata and invoke the standard VaultManager download pipeline
            val vaultMetadata = VaultMetadata(
                videoId = videoId,
                title = finalTitle,
                artist = finalArtist,
                album = finalAlbum,
                durationSeconds = durationSeconds,
                albumArtUrl = thumbnail,
                composer = "",
                description = null,
                mediaId = videoId,
                artistBrowseId = songItem?.artists?.firstOrNull()?.id,
                albumBrowseId = songItem?.album?.id
            )

            val syncResult = LyricsSyncManager.syncLyrics(context, vaultMetadata, isManualForce = false)
            FileLogger.d(TAG, "Lyrics sync result for $videoId: $syncResult")

            // 4. Re-check if lyrics are available now
            val updatedIndex = dao.getSongIndexByVideoId(videoId)
            val updatedSong = dao.getSongByVideoId(videoId)
            val hasLyrics = (updatedIndex != null && updatedIndex.activeLyricFile.isNotBlank()) ||
                    (updatedSong != null && updatedSong.hasLyricsAvailable)

            hasLyrics
        } catch (e: Exception) {
            FileLogger.e(TAG, "Error ensuring lyrics for videoId $videoId", e)
            false
        }
    }

    /**
     * Attempts to find a cached videoId from local vault by title and artist.
     */
    suspend fun findCachedVideoId(context: Context, rawTitle: String, rawArtist: String): String? = withContext(Dispatchers.IO) {
        try {
            val title = cleanMetadata(rawTitle)
            val artist = cleanMetadata(rawArtist)

            val masterPath = LyricsSyncManager.getMasterFolderPath()
            val db = DatabaseProvider.getDatabase(context, masterPath)
            val dao = db.libraryDao()

            var songIndex = dao.getSongIndexByTitleAndArtist(title, artist)
            var songEntity = if (songIndex == null) dao.checkSongExists(title, artist) else null

            if (songIndex == null && songEntity == null) {
                val simplifiedTitle = title.replace(
                    Regex("""(?i)\s*[\(\[](?:official\s*(?:video|audio|music\s*video|lyric\s*video)?|feat\.?|ft\.?).*?[\)\]]"""),
                    ""
                ).trim()
                if (simplifiedTitle.isNotEmpty() && simplifiedTitle != title) {
                    songIndex = dao.getSongIndexByTitleAndArtist(simplifiedTitle, artist)
                    songEntity = if (songIndex == null) dao.checkSongExists(simplifiedTitle, artist) else null
                }
            }

            songIndex?.videoId ?: songEntity?.youtubeVideoId
        } catch (e: Exception) {
            FileLogger.e(TAG, "Error finding cached videoId for \"$rawTitle\" by \"$rawArtist\"", e)
            null
        }
    }

    private fun cleanMetadata(text: String): String {
        return text.replace(Regex("""[\u200B-\u200D\uFEFF\u200E\u200F]"""), "")
            .replace(Regex("""\s+"""), " ")
            .trim()
    }
}
