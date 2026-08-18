package com.metrolist.music.lyrics.vault

import android.content.Context
import android.os.Environment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

object LyricsSyncManager {

    private const val TAG = "LyricsSyncManager"

    private val activeSyncKeys = ConcurrentHashMap.newKeySet<String>()
    private val _activeSyncKeysFlow = MutableStateFlow<Set<String>>(emptySet())
    val activeSyncKeysFlow: StateFlow<Set<String>> = _activeSyncKeysFlow.asStateFlow()

    fun observeIsSyncing(syncKey: String): kotlinx.coroutines.flow.Flow<Boolean> {
        return activeSyncKeysFlow.map { it.contains(syncKey) }
    }
    private val recentSyncTimestamps = ConcurrentHashMap<String, Long>()
    private const val DEBOUNCE_WINDOW_MS = 10_000L

    fun getMasterFolderPath(): String {
        val extStorage = Environment.getExternalStorageDirectory()
        val vaultDir = File(extStorage, "SongSync")
        if (!vaultDir.exists()) {
            vaultDir.mkdirs()
        }
        return vaultDir.absolutePath
    }

    suspend fun syncLyrics(context: Context, metadata: VaultMetadata) = withContext(Dispatchers.IO) {
        val syncKey = metadata.videoId.takeIf { it.isNotBlank() }
            ?: "${metadata.artist}_${metadata.album}_${metadata.title}"

        if (!activeSyncKeys.add(syncKey)) {
            FileLogger.d(TAG, "Sync already in progress for song: ${metadata.title} ($syncKey). Dropping duplicate event.")
            return@withContext
        }
        _activeSyncKeysFlow.value = activeSyncKeys.toSet()

        val lastSync = recentSyncTimestamps[syncKey] ?: 0L
        if (System.currentTimeMillis() - lastSync < DEBOUNCE_WINDOW_MS) {
            FileLogger.d(TAG, "Song was recently synced: ${metadata.title} ($syncKey). Dropping event.")
            activeSyncKeys.remove(syncKey)
            _activeSyncKeysFlow.value = activeSyncKeys.toSet()
            return@withContext
        }

        try {
            val masterPath = getMasterFolderPath()
            val vaultManager = VaultManager(masterPath)
            val db = DatabaseProvider.getDatabase(context, masterPath)
            val dao = db.libraryDao()

            // Step 1: Pre-Fetch Validation (Cooldown & Stop Conditions)
            val parsedVaultDir = vaultManager.getSongParsedVaultDir(metadata.artist, metadata.album, metadata.title)
            val localMeta = vaultManager.readMetadataJson(parsedVaultDir)
            val existingSong = (if (metadata.videoId.isNotBlank()) dao.getSongByVideoId(metadata.videoId) else null)
                ?: dao.checkSongExists(metadata.title, metadata.artist)

            val targetFormats = setOf("lrc", "qrc", "ttml", "elrc")
            val existingFormatsOnDisk = parsedVaultDir.listFiles()?.mapNotNull { file ->
                val ext = file.extension.lowercase()
                if (ext in targetFormats && file.length() > 0) ext else null
            }?.toSet() ?: emptySet()

            val allFormatsPresentOnDisk = targetFormats.all { it in existingFormatsOnDisk }

            // 1a. If all four formats exist locally, set all_formats_present = true and abort fetch
            if (allFormatsPresentOnDisk || localMeta?.all_formats_present == true || existingSong?.all_formats_present == true) {
                if (localMeta != null && !localMeta.all_formats_present) {
                    vaultManager.updateMetadataAllFormatsPresent(parsedVaultDir, true)
                }
                if (existingSong != null && !existingSong.all_formats_present) {
                    dao.updateSongRetryAndFormats(
                        songId = existingSong.id,
                        retryCount = existingSong.retry_count,
                        allFormatsPresent = true,
                        timestamp = existingSong.lastCheckedAt
                    )
                }
                FileLogger.d(TAG, "All 4 target lyric formats (.lrc, .qrc, .ttml, .elrc) exist locally for: ${metadata.title}. Aborting fetch.")
                return@withContext
            }

            // 1b. If retry_count >= 6, abort the fetch (max 6 months of checking)
            val currentRetryCount = localMeta?.retry_count ?: existingSong?.retry_count ?: 0
            if (currentRetryCount >= 6) {
                FileLogger.d(TAG, "Retry limit reached (retry_count=$currentRetryCount >= 6) for: ${metadata.title}. Aborting fetch.")
                return@withContext
            }

            // 1c. Time since last_checked: abort if less than 30 days
            val lastChecked = when {
                localMeta != null && localMeta.last_checked > 0 -> localMeta.last_checked
                existingSong != null && existingSong.lastCheckedAt > 0 -> existingSong.lastCheckedAt
                else -> 0L
            }
            val THIRTY_DAYS_MS = 30L * 24 * 60 * 60 * 1000L
            if (lastChecked > 0 && (System.currentTimeMillis() - lastChecked) < THIRTY_DAYS_MS) {
                val daysAgo = (System.currentTimeMillis() - lastChecked) / (1000 * 60 * 60 * 24)
                FileLogger.d(TAG, "Lyrics checked $daysAgo days ago (< 30 days) for: ${metadata.title}. Aborting fetch.")
                return@withContext
            }

            // Step 2: Turnstile Authentication & SSE Stream Download (with Retry Mechanism)
            val authenticator = TurnstileAuthenticator(context)
            var jwtToken: String? = null
            var tempFile: File? = null

            var attempt = 0
            val maxAttempts = 2

            while (attempt < maxAttempts && tempFile == null) {
                attempt++
                val forceRefresh = (attempt > 1)
                try {
                    if (forceRefresh) {
                        FileLogger.d(TAG, "Forcing fresh WebView challenge on retry attempt $attempt for ${metadata.title}")
                        authenticator.clearCachedToken()
                    }

                    val turnstileToken = authenticator.getTurnstileToken(forceRefresh = forceRefresh)
                    jwtToken = authenticator.verifyToken(turnstileToken)

                    tempFile = BetterLyricsDownloader.downloadSseStream(
                        context = context,
                        videoId = metadata.videoId,
                        songTitle = metadata.title,
                        artistName = metadata.artist,
                        albumName = metadata.album,
                        durationSeconds = metadata.durationSeconds,
                        description = metadata.description ?: "",
                        jwtToken = jwtToken
                    )

                    if (tempFile == null) {
                        FileLogger.e(TAG, "Failed to download SSE stream (returned null) for: ${metadata.title}")
                        break
                    }
                } catch (e: TurnstileVerificationFailed) {
                    FileLogger.e(TAG, "TurnstileVerificationFailed on attempt $attempt for ${metadata.title}: ${e.message}", e)
                    authenticator.clearCachedToken()
                    if (attempt >= maxAttempts) break
                } catch (e: TurnstileTimeout) {
                    FileLogger.e(TAG, "TurnstileTimeout on attempt $attempt for ${metadata.title}: ${e.message}", e)
                    authenticator.clearCachedToken()
                    if (attempt >= maxAttempts) break
                } catch (e: RateLimitException) {
                    throw e // Propagate to prefetcher
                } catch (e: Exception) {
                    FileLogger.e(TAG, "Unexpected exception on attempt $attempt for ${metadata.title}: ${e.message}", e)
                    break
                }
            }

            if (tempFile == null) {
                FileLogger.e(TAG, "Sync process failed to download SSE stream after $attempt attempts for: ${metadata.title}")
                return@withContext
            }

            // Step 3: SSE Parsing & Smart Hashing
            val parser = SseParser()
            val parsedLyricsList = parser.parse(tempFile)
            val now = System.currentTimeMillis()

            val artistId = ensureArtist(dao, metadata.artist, metadata.artistBrowseId)
            val albumName = metadata.album.ifBlank { "Unknown Album" }
            val albumId = ensureAlbum(dao, albumName, metadata.albumBrowseId, artistId)

            // 3a. Empty Check: Discard temp .sse, increment retry_count, update last_checked, wait 30 days
            if (parsedLyricsList.isEmpty()) {
                FileLogger.d(TAG, "Parsed lyrics list is empty for: ${metadata.title}. Discarding temp SSE and incrementing retry_count.")
                tempFile.delete()
                val nextRetry = currentRetryCount + 1

                vaultManager.updateMetadataRetryAndCheck(
                    parsedVaultDir = parsedVaultDir,
                    retryCount = nextRetry,
                    timestamp = now,
                    allFormatsPresent = false,
                    metadata = metadata
                )

                val songId = ensureSong(
                    dao = dao,
                    title = metadata.title,
                    videoId = metadata.videoId,
                    albumId = albumId,
                    durationSeconds = metadata.durationSeconds,
                    hasLyrics = false,
                    retryCount = nextRetry,
                    allFormatsPresent = false
                )

                dao.updateSongRetryAndFormats(
                    songId = songId,
                    retryCount = nextRetry,
                    allFormatsPresent = false,
                    timestamp = now
                )

                if (metadata.videoId.isNotBlank()) {
                    dao.updateSongIndexStatus(metadata.videoId, nextRetry, false)
                }

                return@withContext
            }

            // 3b. Content Hash: SHA-256 hash comparison against existing local lyric files
            val newContentHash = vaultManager.calculateLyricsTextHash(parsedLyricsList)
            val localContentHash = vaultManager.calculateLocalLyricsHash(parsedVaultDir, localMeta)

            if (localContentHash != null && localContentHash == newContentHash) {
                FileLogger.d(TAG, "Lyrics content hash unchanged for: ${metadata.title}. Discarding temp SSE and incrementing retry_count.")
                tempFile.delete()
                val nextRetry = currentRetryCount + 1

                vaultManager.updateMetadataRetryAndCheck(
                    parsedVaultDir = parsedVaultDir,
                    retryCount = nextRetry,
                    timestamp = now,
                    allFormatsPresent = localMeta?.all_formats_present ?: false,
                    metadata = metadata
                )

                val songInDb = (if (metadata.videoId.isNotBlank()) dao.getSongByVideoId(metadata.videoId) else null)
                    ?: dao.getSongByTitleAndAlbum(metadata.title, albumId)
                if (songInDb != null) {
                    dao.updateSongRetryAndFormats(
                        songId = songInDb.id,
                        retryCount = nextRetry,
                        allFormatsPresent = songInDb.all_formats_present,
                        timestamp = now
                    )
                }

                if (metadata.videoId.isNotBlank()) {
                    dao.updateSongIndexStatus(metadata.videoId, nextRetry, localMeta?.all_formats_present ?: false)
                }

                return@withContext
            }

            // Step 4: Saving and Rotating Files
            // 4a. Delete old extracted lyric files
            parsedVaultDir.listFiles()?.filter { file ->
                file.extension.lowercase() in targetFormats
            }?.forEach { it.delete() }

            // 4b. Save newly extracted lyric files
            parsedLyricsList.forEach { lyric ->
                val fileName = "${vaultManager.sanitize(metadata.title)}_${vaultManager.sanitize(lyric.provider)}_${vaultManager.sanitize(lyric.type)}.${lyric.format.lowercase()}"
                val lyricFile = File(parsedVaultDir, fileName)
                lyricFile.writeText(lyric.lyricsText)
            }

            // 4c. Save raw .sse file with version suffix and FIFO queue (max 4 .sse files)
            vaultManager.saveRawSseWithFifoRotation(
                tempFile = tempFile,
                artist = metadata.artist,
                album = metadata.album,
                song = metadata.title,
                maxFiles = 4
            )

            // 4d. Determine if all 4 formats (.lrc, .qrc, .ttml, .elrc) are present
            val presentFormats = parsedLyricsList.map { it.format.lowercase() }.toSet()
            val allFormatsPresent = targetFormats.all { it in presentFormats }

            // 4e. Write metadata.json with ranking, reset retry_count to 0, update last_updated & last_checked
            val activeLyricFile = vaultManager.writeMetadataJsonWithRanking(
                parsedVaultDir = parsedVaultDir,
                metadata = metadata,
                parsedLyricsList = parsedLyricsList,
                lastChecked = now,
                lastUpdated = now,
                retryCount = 0,
                allFormatsPresent = allFormatsPresent
            )

            // 4f. Update Room DB
            val songId = ensureSong(
                dao = dao,
                title = metadata.title,
                videoId = metadata.videoId,
                albumId = albumId,
                durationSeconds = metadata.durationSeconds,
                hasLyrics = true,
                retryCount = 0,
                allFormatsPresent = allFormatsPresent
            )

            dao.updateSongCheckStatus(
                songId = songId,
                timestamp = now,
                hasLyrics = true,
                retryCount = 0,
                allFormatsPresent = allFormatsPresent
            )

            if (activeLyricFile != null) {
                dao.insertOrUpdateSongIndex(
                    SongIndexEntity(
                        title = metadata.title,
                        artist = metadata.artist,
                        videoId = metadata.videoId,
                        folderPath = parsedVaultDir.absolutePath,
                        activeLyricFile = activeLyricFile,
                        retry_count = 0,
                        all_formats_present = allFormatsPresent
                    )
                )
            }

            dao.deleteFilesForSong(songId)
            parsedLyricsList.forEach { lyric ->
                val fileName = "${vaultManager.sanitize(metadata.title)}_${vaultManager.sanitize(lyric.provider)}_${vaultManager.sanitize(lyric.type)}.${lyric.format.lowercase()}"
                val relPath = "${vaultManager.sanitize(metadata.artist)}/${vaultManager.sanitize(metadata.album)}/${vaultManager.sanitize(metadata.title)}/$fileName"
                dao.insertFile(
                    FileEntity(
                        songId = songId,
                        providerName = lyric.provider,
                        syncLevel = lyric.type,
                        relativePath = relPath
                    )
                )
            }

            FileLogger.d(TAG, "Successfully archived lyrics and metadata for: ${metadata.title}")
        } catch (e: Exception) {
            FileLogger.e(TAG, "Error in syncLyrics pipeline for ${metadata.title}", e)
        } finally {
            recentSyncTimestamps[syncKey] = System.currentTimeMillis()
            activeSyncKeys.remove(syncKey)
            _activeSyncKeysFlow.value = activeSyncKeys.toSet()
        }
    }

    suspend fun reindexDatabase(context: Context): Int = withContext(Dispatchers.IO) {
        val masterPath = getMasterFolderPath()
        val vaultManager = VaultManager(masterPath)
        val db = DatabaseProvider.getDatabase(context, masterPath)

        // Clear the database completely to prevent duplicate entries and stale data
        db.clearAllTables()

        val dao = db.libraryDao()

        val lyricsDatabaseDir = File(masterPath, "LyricsDatabase")
        if (!lyricsDatabaseDir.exists() || !lyricsDatabaseDir.isDirectory) {
            FileLogger.d(TAG, "LyricsDatabase directory does not exist for re-indexing.")
            return@withContext 0
        }

        val metadataFiles = lyricsDatabaseDir.walkTopDown()
            .filter { it.isFile && it.name == "metadata.json" }
            .toList()

        var reindexedCount = 0

        for (metadataFile in metadataFiles) {
            try {
                val jsonText = metadataFile.readText()
                val json = JSONObject(jsonText)

                val videoId = json.optString("videoId").takeIf { it.isNotBlank() }
                val title = json.optString("title")
                val artist = json.optString("artist")
                val album = json.optString("album")
                val durationSeconds = json.optInt("durationSeconds", 0)
                val artistBrowseId = json.optString("artistBrowseId").takeIf { it.isNotBlank() }
                val albumBrowseId = json.optString("albumBrowseId").takeIf { it.isNotBlank() }
                val retryCount = json.optInt("retry_count", 0)
                val allFormatsPresent = json.optBoolean("all_formats_present", false)

                if (title.isBlank() || artist.isBlank()) continue

                val artistId = ensureArtist(dao, artist, artistBrowseId)
                val albumName = album.ifBlank { "Unknown Album" }
                val albumId = ensureAlbum(dao, albumName, albumBrowseId, artistId)

                val lyricsArray = json.optJSONArray("lyrics")
                val hasLyrics = lyricsArray != null && lyricsArray.length() > 0

                val songId = ensureSong(
                    dao = dao,
                    title = title,
                    videoId = videoId,
                    albumId = albumId,
                    durationSeconds = durationSeconds,
                    hasLyrics = hasLyrics,
                    retryCount = retryCount,
                    allFormatsPresent = allFormatsPresent
                )

                var activeLyricFile = json.optString("active_lyric_file").takeIf { it.isNotBlank() }

                if (lyricsArray != null && lyricsArray.length() > 0) {
                    dao.deleteFilesForSong(songId)
                    for (i in 0 until lyricsArray.length()) {
                        val lyricObj = lyricsArray.optJSONObject(i) ?: continue
                        val provider = lyricObj.optString("provider")
                        val type = lyricObj.optString("type")
                        val fileName = lyricObj.optString("file_name")
                        
                        if (activeLyricFile == null && fileName.isNotBlank()) {
                            activeLyricFile = fileName
                        }

                        if (provider.isNotBlank() && type.isNotBlank() && fileName.isNotBlank()) {
                            val relPath = "${vaultManager.sanitize(artist)}/${vaultManager.sanitize(album)}/${vaultManager.sanitize(title)}/$fileName"
                            dao.insertFile(
                                FileEntity(
                                    songId = songId,
                                    providerName = provider,
                                    syncLevel = type,
                                    relativePath = relPath
                                )
                            )
                        }
                    }
                }

                if (!videoId.isNullOrBlank()) {
                    dao.insertOrUpdateSongIndex(
                        SongIndexEntity(
                            title = title,
                            artist = artist,
                            videoId = videoId,
                            folderPath = metadataFile.parentFile?.absolutePath ?: "",
                            activeLyricFile = activeLyricFile ?: "",
                            retry_count = retryCount,
                            all_formats_present = allFormatsPresent
                        )
                    )
                }

                reindexedCount++
            } catch (e: Exception) {
                FileLogger.e(TAG, "Error parsing metadata file during reindex: ${metadataFile.absolutePath}", e)
            }
        }

        FileLogger.d(TAG, "Re-indexed $reindexedCount songs in Lyrivault Room database.")
        reindexedCount
    }

    private suspend fun ensureArtist(dao: LibraryDao, artistName: String, browseId: String?): Int {
        val existing = dao.getArtistByName(artistName)
        if (existing != null) return existing.id

        val insertedId = dao.insertArtist(
            ArtistEntity(name = artistName, youtubeArtistId = browseId)
        ).toInt()

        if (insertedId != -1) return insertedId

        return dao.getArtistByName(artistName)?.id
            ?: error("Failed to ensure artist for: $artistName")
    }

    private suspend fun ensureAlbum(dao: LibraryDao, albumName: String, browseId: String?, artistId: Int): Int {
        val existing = dao.getAlbumByNameAndArtist(albumName, artistId)
        if (existing != null) return existing.id

        val insertedId = dao.insertAlbum(
            AlbumEntity(name = albumName, youtubeAlbumId = browseId, artistId = artistId)
        ).toInt()

        if (insertedId != -1) return insertedId

        return dao.getAlbumByNameAndArtist(albumName, artistId)?.id
            ?: error("Failed to ensure album for: $albumName")
    }

    private suspend fun ensureSong(
        dao: LibraryDao,
        title: String,
        videoId: String?,
        albumId: Int,
        durationSeconds: Int,
        hasLyrics: Boolean,
        retryCount: Int = 0,
        allFormatsPresent: Boolean = false
    ): Int {
        val existingSong = (if (!videoId.isNullOrEmpty()) dao.getSongByVideoId(videoId) else null)
            ?: dao.getSongByTitleAndAlbum(title, albumId)

        if (existingSong != null) {
            dao.updateSongCheckStatus(
                songId = existingSong.id,
                timestamp = System.currentTimeMillis(),
                hasLyrics = hasLyrics,
                retryCount = retryCount,
                allFormatsPresent = allFormatsPresent
            )
            return existingSong.id
        }

        val insertedId = dao.insertSong(
            SongEntity(
                title = title,
                durationSeconds = durationSeconds,
                youtubeVideoId = videoId,
                albumId = albumId,
                hasLyricsAvailable = hasLyrics,
                lastCheckedAt = System.currentTimeMillis(),
                retry_count = retryCount,
                all_formats_present = allFormatsPresent
            )
        ).toInt()

        if (insertedId != -1) return insertedId

        val requeriedSong = (if (!videoId.isNullOrEmpty()) dao.getSongByVideoId(videoId) else null)
            ?: dao.getSongByTitleAndAlbum(title, albumId)

        if (requeriedSong != null) {
            dao.updateSongCheckStatus(
                songId = requeriedSong.id,
                timestamp = System.currentTimeMillis(),
                hasLyrics = hasLyrics,
                retryCount = retryCount,
                allFormatsPresent = allFormatsPresent
            )
            return requeriedSong.id
        }

        error("Failed to ensure song for: $title")
    }
}


