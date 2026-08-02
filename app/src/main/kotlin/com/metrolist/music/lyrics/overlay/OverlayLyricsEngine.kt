package com.metrolist.music.lyrics.overlay

import android.content.Context
import android.util.LruCache
import com.metrolist.music.lyrics.LyricsEntry
import com.metrolist.music.lyrics.LyricsUtils
import com.metrolist.music.lyrics.vault.DatabaseProvider
import com.metrolist.music.lyrics.vault.LyricsSyncManager
import com.metrolist.music.lyrics.vault.VaultManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

data class OverlayLyricWord(
    val text: String,
    val startTimeMs: Long,
    val endTimeMs: Long,
    val hasTrailingSpace: Boolean
)

data class OverlayLyricLine(
    val text: String,
    val startTimeMs: Long,
    val endTimeMs: Long,
    val words: List<OverlayLyricWord>,
    val agent: String?,
    val isBackground: Boolean
)

fun List<LyricsEntry>.toOverlayLyricLines(): List<OverlayLyricLine> {
    return this.mapIndexed { index, entry ->
        val nextEntry = this.getOrNull(index + 1)
        val endTimeMs = if (!entry.words.isNullOrEmpty()) {
            (entry.words.last().endTime * 1000).toLong()
        } else {
            nextEntry?.time ?: (entry.time + 10_000L)
        }

        val overlayWords = entry.words?.map { word ->
            OverlayLyricWord(
                text = word.text,
                startTimeMs = (word.startTime * 1000).toLong(),
                endTimeMs = (word.endTime * 1000).toLong(),
                hasTrailingSpace = word.hasTrailingSpace
            )
        } ?: emptyList()

        OverlayLyricLine(
            text = entry.text,
            startTimeMs = entry.time,
            endTimeMs = endTimeMs,
            words = overlayWords,
            agent = entry.agent,
            isBackground = entry.isBackground
        )
    }
}

data class ParsedOverlayLyrics(
    val videoId: String,
    val folderPath: String,
    val filename: String,
    val lines: List<OverlayLyricLine>
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
                lines = entries.toOverlayLyricLines()
            )
            lruCache.put(videoId, parsedOverlayLyrics)
            parsedOverlayLyrics
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Implements Data Fetching using reactive Flow without arbitrary timeouts.
     */
    fun observeLyricsForTrack(context: Context, videoId: String): Flow<ParsedOverlayLyrics?> = flow {
        if (videoId.isBlank()) {
            emit(null)
            return@flow
        }

        val cached = lruCache.get(videoId)
        if (cached != null) {
            emit(cached)
            return@flow
        }

        val masterPath = LyricsSyncManager.getMasterFolderPath()
        val dao = DatabaseProvider.getDatabase(context, masterPath).libraryDao()
        
        suspend fun tryLoadFromDb(): ParsedOverlayLyrics? {
            val index = dao.getSongByVideoId(videoId)
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
                            resolvedActiveFile = metadata.lyrics.minByOrNull { it.rank }?.filename
                        }
                    } catch (e: Exception) {
                        resolvedActiveFile = index.activeLyricFile
                    }
                } else {
                    resolvedActiveFile = index.activeLyricFile
                }

                if (!resolvedActiveFile.isNullOrBlank()) {
                    return loadDirectLyricFile(videoId, index.folderPath, resolvedActiveFile)
                }
            }
            return null
        }

        // 1. Try immediate load
        val immediate = tryLoadFromDb()
        if (immediate != null) {
            emit(immediate)
            return@flow
        }

        // 2. Wait for sync to finish (if syncing)
        val isSyncing = LyricsSyncManager.observeIsSyncing(videoId).first()
        if (isSyncing) {
            // Wait until isSyncing becomes false
            LyricsSyncManager.observeIsSyncing(videoId).first { !it }
            
            // Re-query database once
            emit(tryLoadFromDb())
        } else {
            // Not syncing, and immediate was null. Emit null.
            emit(null)
        }
    }
}
