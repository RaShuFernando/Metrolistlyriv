package com.metrolist.music.lyrics.overlay

import android.content.Context
import android.util.LruCache
import android.util.Log
import android.net.Uri
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

import com.metrolist.music.lyrics.overlay.ParserFactory

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

data class OverlayLyricWord(
    val text: String,
    val startTimeMs: Long,
    val endTimeMs: Long,
    val hasTrailingSpace: Boolean,
    val isBackground: Boolean = false,
    val agent: String? = null
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
                hasTrailingSpace = word.hasTrailingSpace,
                isBackground = word.isBackground
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

    fun clearCacheForVideoId(videoId: String) {
        lruCache.remove(videoId)
    }

    suspend fun loadDirectLyricFile(
        context: Context,
        videoId: String,
        folderPath: String,
        activeLyricFile: String,
        offsetMs: Long = 0L
    ): ParsedOverlayLyrics? = withContext(Dispatchers.IO) {
        val path = if (activeLyricFile.startsWith("content://") || activeLyricFile.startsWith("/")) {
            activeLyricFile
        } else if (folderPath.startsWith("content://")) {
            if (folderPath.endsWith("/")) "$folderPath$activeLyricFile" else "$folderPath/$activeLyricFile"
        } else {
            File(folderPath, activeLyricFile).absolutePath
        }

        Log.e("LyricsEngine", "Attempting to load lyrics from: $path")
        try {
            val text = if (path.startsWith("content://")) {
                Log.e("LyricsEngine", "Resolution method: URI")
                context.applicationContext.contentResolver.openInputStream(Uri.parse(path))?.use {
                    it.bufferedReader().readText()
                } ?: run {
                    Log.e("LyricsEngine", "Failed to open InputStream for URI")
                    return@withContext null
                }
            } else {
                Log.e("LyricsEngine", "Resolution method: File")
                val file = File(path)
                if (!file.exists()) {
                    Log.e("LyricsEngine", "File does not exist: $path")
                    return@withContext null
                }
                file.readText()
            }

            val parser = ParserFactory.getParser(activeLyricFile)
            val lines = if (parser != null) {
                parser.parse(text)
            } else {
                val entries = LyricsUtils.parseLyrics(text)
                if (entries.isEmpty()) {
                    Log.e("LyricsEngine", "Parsed 0 lines, returning null")
                    return@withContext null
                }
                entries.toOverlayLyricLines()
            }

            if (lines.isEmpty()) {
                Log.e("LyricsEngine", "Parsed 0 lines, returning null")
                return@withContext null
            }

            val offsetLines = lines.map { line ->
                line.copy(
                    startTimeMs = line.startTimeMs + offsetMs,
                    endTimeMs = line.endTimeMs + offsetMs,
                    words = line.words.map { word ->
                        word.copy(
                            startTimeMs = word.startTimeMs + offsetMs,
                            endTimeMs = word.endTimeMs + offsetMs
                        )
                    }
                )
            }

            Log.e("LyricsEngine", "Successfully parsed ${offsetLines.size} lines")

            val parsedOverlayLyrics = ParsedOverlayLyrics(
                videoId = videoId,
                folderPath = folderPath,
                filename = activeLyricFile,
                lines = offsetLines
            )
            lruCache.put(videoId, parsedOverlayLyrics)
            parsedOverlayLyrics
        } catch (e: Exception) {
            Log.e("LyricsEngine", "Parser crashed: ${e.message}")
            null
        }
    }

    /**
     * Implements Data Fetching using reactive Flow without arbitrary timeouts.
     */
    fun observeLyricsForTrack(context: Context, videoId: String): Flow<ParsedOverlayLyrics?> {
        if (videoId.isBlank()) {
            return kotlinx.coroutines.flow.flowOf(null)
        }

        val masterPath = LyricsSyncManager.getMasterFolderPath()
        val dao = DatabaseProvider.getDatabase(context, masterPath).libraryDao()
        
        return kotlinx.coroutines.flow.combine(
            dao.observeSongIndexByVideoId(videoId),
            LyricsSyncManager.observeIsSyncing(videoId)
        ) { index, isSyncing ->
            
            var parsed: ParsedOverlayLyrics? = null
            
            if (index != null && index.folderPath.isNotBlank()) {
                var metadataContent: String? = null
                val metadataPath = if (index.folderPath.startsWith("content://")) {
                    if (index.folderPath.endsWith("/")) "${index.folderPath}metadata.json" else "${index.folderPath}/metadata.json"
                } else {
                    File(index.folderPath, "metadata.json").absolutePath
                }

                metadataContent = withContext(Dispatchers.IO) {
                    try {
                        if (metadataPath.startsWith("content://")) {
                            context.applicationContext.contentResolver.openInputStream(Uri.parse(metadataPath))?.use {
                                it.bufferedReader().readText()
                            }
                        } else {
                            val metadataFile = File(metadataPath)
                            if (metadataFile.exists()) {
                                metadataFile.readText()
                            } else null
                        }
                    } catch (e: Exception) {
                        Log.e("LyricsEngine", "Failed to read metadata: ${e.message}")
                        null
                    }
                }

                var resolvedActiveFile: String? = null
                var resolvedOffsetMs: Long = 0L
                
                if (metadataContent != null) {
                    try {
                        val metadata = VaultManager.jsonSerializer.decodeFromString(
                            com.metrolist.music.lyrics.vault.LyricMetadata.serializer(), 
                            metadataContent!!
                        )
                        
                        if (!metadata.active_lyric_file.isNullOrBlank()) {
                            resolvedActiveFile = metadata.active_lyric_file
                        }
                        
                        if (resolvedActiveFile == null) {
                            resolvedActiveFile = metadata.lyrics.minByOrNull { it.rank }?.filename
                        }

                        if (resolvedActiveFile != null) {
                            resolvedOffsetMs = metadata.lyrics.find { it.filename == resolvedActiveFile }?.offsetMs ?: 0L
                        }
                    } catch (e: Exception) {
                        Log.e("LyricsEngine", "Failed to parse metadata: ${e.message}")
                        resolvedActiveFile = index.activeLyricFile
                    }
                } else {
                    resolvedActiveFile = index.activeLyricFile
                }

                if (!resolvedActiveFile.isNullOrBlank()) {
                    parsed = loadDirectLyricFile(context, videoId, index.folderPath, resolvedActiveFile!!, resolvedOffsetMs)
                }
            }
            
            // If parsed is not null, it will emit the lyrics.
            // If parsed is null and isSyncing is true, we emit null. The UI will show Loading/Fetching.
            // If parsed is null and isSyncing is false, we emit null. The UI will eventually show NotFound.
            parsed
        }
    }
}
