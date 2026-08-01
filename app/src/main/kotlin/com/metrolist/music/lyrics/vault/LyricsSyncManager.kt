package com.metrolist.music.lyrics.vault

import android.content.Context
import android.os.Environment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

object LyricsSyncManager {

    private const val TAG = "LyricsSyncManager"

    private val activeSyncKeys = ConcurrentHashMap.newKeySet<String>()
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

        val lastSync = recentSyncTimestamps[syncKey] ?: 0L
        if (System.currentTimeMillis() - lastSync < DEBOUNCE_WINDOW_MS) {
            FileLogger.d(TAG, "Song was recently synced: ${metadata.title} ($syncKey). Dropping event.")
            activeSyncKeys.remove(syncKey)
            return@withContext
        }

        try {
            val masterPath = getMasterFolderPath()
            val vaultManager = VaultManager(masterPath)
            val db = DatabaseProvider.getDatabase(context, masterPath)
            val dao = db.libraryDao()

            // Step a) Local Room DB Cache Check
            val existingSong = dao.getSongByVideoId(metadata.videoId)
                ?: dao.checkSongExists(metadata.title, metadata.artist)

            if (existingSong != null && existingSong.hasLyricsAvailable && existingSong.lastCheckedAt > 0) {
                val dayInMs = 24 * 60 * 60 * 1000L
                if (System.currentTimeMillis() - existingSong.lastCheckedAt < dayInMs) {
                    FileLogger.d(TAG, "Lyrics already cached locally for song: ${metadata.title}")
                    return@withContext
                }
            }

            // Step b) Turnstile Authentication & SSE Stream Download (with Retry Mechanism)
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
                } catch (e: Exception) {
                    FileLogger.e(TAG, "Unexpected exception on attempt $attempt for ${metadata.title}: ${e.message}", e)
                    break
                }
            }

            if (tempFile == null) {
                FileLogger.e(TAG, "Sync process failed to download SSE stream after $attempt attempts for: ${metadata.title}")
                return@withContext
            }

            // Step c) Version raw SSE via VaultManager
            val sseFile = vaultManager.processNewSseFile(tempFile, metadata.artist, metadata.album, metadata.title)
            val parsedVaultDir = vaultManager.getSongParsedVaultDir(metadata.artist, metadata.album, metadata.title)

            // SQLite Foreign Key crash prevention using ensure methods
            val artistId = ensureArtist(dao, metadata.artist, metadata.artistBrowseId)
            val albumName = metadata.album.ifBlank { "Unknown Album" }
            val albumId = ensureAlbum(dao, albumName, metadata.albumBrowseId, artistId)

            if (sseFile == null) {
                // Duplicate SSE hash detected: processNewSseFile already updated last_checked in metadata.json & changelog.json
                FileLogger.d(TAG, "Duplicate SSE stream detected for: ${metadata.title}. Updated last_checked.")
                
                // Keep Room DB lastCheckedAt up-to-date
                val songInDb = dao.getSongByVideoId(metadata.videoId)
                    ?: dao.getSongByTitleAndAlbum(metadata.title, albumId)
                if (songInDb != null) {
                    dao.updateSongCheckStatus(songInDb.id, System.currentTimeMillis(), songInDb.hasLyricsAvailable)
                } else {
                    ensureSong(dao, metadata.title, metadata.videoId, albumId, metadata.durationSeconds, false)
                }
                return@withContext
            }

            // Step d) Parse new SSE file, save formatted lyrics & metadata.json
            val parsedLyricsList = if (sseFile.exists()) {
                val parser = SseParser()
                parser.parse(sseFile)
            } else {
                emptyList()
            }

            // Save formatted lyric files
            parsedLyricsList.forEach { lyric ->
                val fileName = "${vaultManager.sanitize(metadata.title)}_${vaultManager.sanitize(lyric.provider)}_${vaultManager.sanitize(lyric.type)}.${lyric.format.lowercase()}"
                val lyricFile = File(parsedVaultDir, fileName)
                lyricFile.writeText(lyric.lyricsText)
            }

            // Write metadata.json with last_checked, last_updated, and parsed lyrics array
            val now = System.currentTimeMillis()
            vaultManager.writeMetadataJson(
                parsedVaultDir = parsedVaultDir,
                metadata = metadata,
                parsedLyricsList = parsedLyricsList,
                lastChecked = now,
                lastUpdated = now
            )

            // Room Database update
            val hasLyrics = parsedLyricsList.isNotEmpty()
            val songId = ensureSong(
                dao = dao,
                title = metadata.title,
                videoId = metadata.videoId,
                albumId = albumId,
                durationSeconds = metadata.durationSeconds,
                hasLyrics = hasLyrics
            )

            if (parsedLyricsList.isNotEmpty()) {
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
            }

            FileLogger.d(TAG, "Successfully archived lyrics and metadata for: ${metadata.title}")
        } catch (e: Exception) {
            FileLogger.e(TAG, "Error in syncLyrics pipeline for ${metadata.title}", e)
        } finally {
            recentSyncTimestamps[syncKey] = System.currentTimeMillis()
            activeSyncKeys.remove(syncKey)
        }
    }

    suspend fun reindexDatabase(context: Context): Int = withContext(Dispatchers.IO) {
        val masterPath = getMasterFolderPath()
        val vaultManager = VaultManager(masterPath)
        val db = DatabaseProvider.getDatabase(context, masterPath)
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
                    hasLyrics = hasLyrics
                )

                if (lyricsArray != null && lyricsArray.length() > 0) {
                    dao.deleteFilesForSong(songId)
                    for (i in 0 until lyricsArray.length()) {
                        val lyricObj = lyricsArray.optJSONObject(i) ?: continue
                        val provider = lyricObj.optString("provider")
                        val type = lyricObj.optString("type")
                        val fileName = lyricObj.optString("file_name")
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
        hasLyrics: Boolean
    ): Int {
        val existingSong = (if (!videoId.isNullOrEmpty()) dao.getSongByVideoId(videoId) else null)
            ?: dao.getSongByTitleAndAlbum(title, albumId)

        if (existingSong != null) {
            dao.updateSongCheckStatus(existingSong.id, System.currentTimeMillis(), hasLyrics)
            return existingSong.id
        }

        val insertedId = dao.insertSong(
            SongEntity(
                title = title,
                durationSeconds = durationSeconds,
                youtubeVideoId = videoId,
                albumId = albumId,
                hasLyricsAvailable = hasLyrics,
                lastCheckedAt = System.currentTimeMillis()
            )
        ).toInt()

        if (insertedId != -1) return insertedId

        val requeriedSong = (if (!videoId.isNullOrEmpty()) dao.getSongByVideoId(videoId) else null)
            ?: dao.getSongByTitleAndAlbum(title, albumId)

        if (requeriedSong != null) {
            dao.updateSongCheckStatus(requeriedSong.id, System.currentTimeMillis(), hasLyrics)
            return requeriedSong.id
        }

        error("Failed to ensure song for: $title")
    }
}

