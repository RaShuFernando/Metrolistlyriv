package com.metrolist.music.lyrics.vault

import android.content.Context
import android.os.Environment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import java.io.File

object LyricsSyncManager {

    private const val TAG = "LyricsSyncManager"

    fun getMasterFolderPath(): String {
        val extStorage = Environment.getExternalStorageDirectory()
        val vaultDir = File(extStorage, "Lyrivault")
        if (!vaultDir.exists()) {
            vaultDir.mkdirs()
        }
        return vaultDir.absolutePath
    }

    suspend fun syncLyrics(context: Context, metadata: VaultMetadata) = withContext(Dispatchers.IO) {
        val masterPath = getMasterFolderPath()
        val vaultManager = VaultManager(masterPath)
        val db = DatabaseProvider.getDatabase(context, masterPath)
        val dao = db.libraryDao()

        try {
            // Step a) Local Room DB Cache Check
            val existingSong = dao.getSongByVideoId(metadata.videoId)
                ?: dao.checkSongExists(metadata.title, metadata.artist)

            if (existingSong != null && existingSong.hasLyricsAvailable && existingSong.lastCheckedAt > 0) {
                val dayInMs = 24 * 60 * 60 * 1000L
                if (System.currentTimeMillis() - existingSong.lastCheckedAt < dayInMs) {
                    Timber.tag(TAG).d("Lyrics already cached locally for song: ${metadata.title}")
                    return@withContext
                }
            }

            // Step b) Turnstile Authentication
            val authenticator = TurnstileAuthenticator(context)
            val token = withTimeoutOrNull(30_000L) {
                authenticator.getTurnstileToken().first()
            }

            if (token.isNullOrEmpty()) {
                vaultManager.writeErrorLog("Turnstile", "Failed to get Turnstile token within timeout for: ${metadata.title}")
                return@withContext
            }

            val jwtToken = authenticator.verifyToken(token)
            if (jwtToken.isNullOrEmpty()) {
                vaultManager.writeErrorLog("Turnstile", "Turnstile token verification failed for: ${metadata.title}")
                return@withContext
            }

            // Step c) Fetch SSE stream via BetterLyricsDownloader
            val tempFile = BetterLyricsDownloader.downloadSseStream(
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
                vaultManager.writeErrorLog("Downloader", "Failed to download SSE stream for: ${metadata.title}")
                return@withContext
            }

            // Step d) Version raw SSE via VaultManager
            val sseFile = vaultManager.processNewSseFile(tempFile, metadata.artist, metadata.album, metadata.title)

            // Step e) Parsing, saving files, writing metadata.json, and updating Room DB
            val parsedVaultDir = vaultManager.getSongParsedVaultDir(metadata.artist, metadata.album, metadata.title)
            vaultManager.writeMetadataJson(parsedVaultDir, metadata)

            val parsedLyricsList = if (sseFile != null && sseFile.exists()) {
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

            // Room Database update
            val existingArtist = dao.getArtistByName(metadata.artist)
            val artistId = existingArtist?.id ?: dao.insertArtist(
                ArtistEntity(name = metadata.artist, youtubeArtistId = metadata.artistBrowseId)
            ).toInt()

            val albumName = metadata.album.ifBlank { "Unknown Album" }
            val existingAlbum = dao.getAlbumByNameAndArtist(albumName, artistId)
            val albumId = existingAlbum?.id ?: dao.insertAlbum(
                AlbumEntity(name = albumName, youtubeAlbumId = metadata.albumBrowseId, artistId = artistId)
            ).toInt()

            val songInDb = dao.getSongByVideoId(metadata.videoId)
                ?: dao.getSongByTitleAndAlbum(metadata.title, albumId)

            val hasLyrics = parsedLyricsList.isNotEmpty()
            val songId = if (songInDb == null) {
                dao.insertSong(
                    SongEntity(
                        title = metadata.title,
                        durationSeconds = metadata.durationSeconds,
                        youtubeVideoId = metadata.videoId,
                        albumId = albumId,
                        hasLyricsAvailable = hasLyrics,
                        lastCheckedAt = System.currentTimeMillis()
                    )
                ).toInt()
            } else {
                dao.updateSongCheckStatus(songInDb.id, System.currentTimeMillis(), hasLyrics)
                songInDb.id
            }

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

            Timber.tag(TAG).d("Successfully archived lyrics and metadata for: ${metadata.title}")
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Error in syncLyrics pipeline for ${metadata.title}")
            vaultManager.writeErrorLog("Pipeline", "Exception: ${e.message}")
        }
    }
}
