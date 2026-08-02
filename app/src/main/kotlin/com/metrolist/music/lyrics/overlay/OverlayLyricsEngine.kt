package com.metrolist.music.lyrics.overlay

import android.content.Context
import android.util.LruCache
import com.metrolist.music.lyrics.LyricsEntry
import com.metrolist.music.lyrics.LyricsUtils
import com.metrolist.music.lyrics.vault.DatabaseProvider
import com.metrolist.music.lyrics.vault.LyricsSyncManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

data class ParsedOverlayLyrics(
    val videoId: String,
    val folderPath: String,
    val filename: String,
    val entries: List<LyricsEntry>
)

object OverlayLyricsEngine {

    private const val LRU_CACHE_CAPACITY = 20
    private val lruCache = LruCache<String, ParsedOverlayLyrics>(LRU_CACHE_CAPACITY)

    fun getCachedLyrics(videoId: String): ParsedOverlayLyrics? {
        return lruCache.get(videoId)
    }

    fun putInCache(videoId: String, lyrics: ParsedOverlayLyrics) {
        lruCache.put(videoId, lyrics)
    }

    fun clearCache() {
        lruCache.evictAll()
    }

    suspend fun loadDirectLyricFile(
        videoId: String,
        folderPath: String,
        activeLyricFile: String
    ): ParsedOverlayLyrics? = withContext(Dispatchers.IO) {
        try {
            val file = File(folderPath, activeLyricFile)
            if (!file.exists()) return@withContext null
            val text = file.readText()
            val entries = LyricsUtils.parseLyrics(text)
            if (entries.isEmpty()) return@withContext null

            val parsedOverlayLyrics = ParsedOverlayLyrics(
                videoId = videoId,
                folderPath = folderPath,
                filename = activeLyricFile,
                entries = entries
            )
            lruCache.put(videoId, parsedOverlayLyrics)
            parsedOverlayLyrics
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Implements Data Fetching & The 30-Second Rule using reactive Flow.
     * 1. Checks LRU Cache first for zero disk I/O on repeated plays.
     * 2. If missing, observes Room DB index flow with 30,000 ms timeout.
     * 3. If file arrives within 30s, reads & parses file directly, caches it, and emits.
     * 4. If 30s elapses, cancels silently and emits null.
     */
    fun observeLyricsForTrack(context: Context, videoId: String): Flow<ParsedOverlayLyrics?> = flow {
        if (videoId.isBlank()) {
            emit(null)
            return@flow
        }

        // Step 1: Check LRU Cache
        val cached = lruCache.get(videoId)
        if (cached != null) {
            emit(cached)
            return@flow
        }

        // Step 2 & 3 & 4: 30-Second Rule Coroutine
        val masterPath = LyricsSyncManager.getMasterFolderPath()
        val dao = DatabaseProvider.getDatabase(context, masterPath).libraryDao()

        val result = withTimeoutOrNull(30_000L) {
            var loadedLyrics: ParsedOverlayLyrics? = null
            dao.observeSongIndexByVideoId(videoId).collect { index ->
                if (index != null && index.folderPath.isNotBlank()) {
                    val metadataFile = File(index.folderPath, "metadata.json")
                    var resolvedActiveFile: String? = null
                    
                    if (metadataFile.exists()) {
                        try {
                            val content = metadataFile.readText()
                            val metadata = VaultManager.jsonSerializer.decodeFromString(
                                com.metrolist.music.lyrics.vault.LyricMetadata.serializer(), 
                                content
                            )
                            
                            if (!metadata.active_lyric_file.isNullOrBlank()) {
                                val testFile = File(index.folderPath, metadata.active_lyric_file)
                                if (testFile.exists()) {
                                    resolvedActiveFile = metadata.active_lyric_file
                                }
                            }
                            
                            if (resolvedActiveFile == null) {
                                resolvedActiveFile = com.metrolist.music.lyrics.vault.LyricsRanking.selectActiveLyricFile(metadata.lyrics)
                            }
                        } catch (e: Exception) {
                            resolvedActiveFile = index.activeLyricFile
                        }
                    } else {
                        resolvedActiveFile = index.activeLyricFile
                    }

                    if (!resolvedActiveFile.isNullOrBlank()) {
                        val directLyrics = loadDirectLyricFile(videoId, index.folderPath, resolvedActiveFile)
                        if (directLyrics != null) {
                            loadedLyrics = directLyrics
                            return@collect // Got valid lyrics file
                        }
                    }
                }
            }
            loadedLyrics
        }

        emit(result)
    }
}
