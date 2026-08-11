package com.metrolist.music.lyrics.vault

import java.io.File
import java.security.MessageDigest
import kotlinx.serialization.Serializable
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

data class VaultMetadata(
    val videoId: String,
    val title: String,
    val artist: String,
    val album: String,
    val durationSeconds: Int,
    val albumArtUrl: String,
    val composer: String,
    val description: String? = null,
    val mediaId: String,
    val releaseYear: Int? = null,
    val trackNumber: Int? = null,
    val artistBrowseId: String? = null,
    val albumBrowseId: String? = null,
    val isExplicit: Boolean = false
)

@Serializable
data class Changelog(
    val latest_version: Int = 0,
    val active_version_hash: String = "",
    val last_updated: Long = 0L,
    val last_checked: Long = 0L
)

class VaultManager(private val masterFolderPath: String) {

    companion object {
        val metadataMutexMap = java.util.concurrent.ConcurrentHashMap<String, Mutex>()
        val jsonSerializer = Json {
            ignoreUnknownKeys = true
            prettyPrint = true
            encodeDefaults = true
        }
    }

    private val masterDir = File(masterFolderPath)
    private val rawSseDir = File(masterDir, "RawSse")
    private val lyricsDatabaseDir = File(masterDir, "LyricsDatabase")
    
    init {
        masterDir.mkdirs()
        rawSseDir.mkdirs()
        lyricsDatabaseDir.mkdirs()
    }

    fun sanitize(name: String): String {
        return name.replace("[\\\\/:*?\"<>|]".toRegex(), "_")
            .trimEnd { it == '.' || it.isWhitespace() }
            .trim()
    }

    private fun getSongRawVaultDir(artist: String, album: String, song: String): File {
        val parts = listOf(artist, album, song).filter { it.isNotBlank() }.map { sanitize(it) }
        val dir = File(rawSseDir, parts.joinToString("/"))
        dir.mkdirs()
        return dir
    }

    fun getSongParsedVaultDir(artist: String, album: String, song: String): File {
        val parts = listOf(artist, album, song).filter { it.isNotBlank() }.map { sanitize(it) }
        val dir = File(lyricsDatabaseDir, parts.joinToString("/"))
        dir.mkdirs()
        return dir
    }

    fun calculateSha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { fis ->
            val buffer = ByteArray(8192)
            var bytesRead: Int
            while (fis.read(buffer).also { bytesRead = it } != -1) {
                digest.update(buffer, 0, bytesRead)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun updateMetadataLastCheckedOnly(parsedVaultDir: File, timestamp: Long = System.currentTimeMillis()) {
        val metadataFile = File(parsedVaultDir, "metadata.json")
        if (metadataFile.exists()) {
            try {
                val content = metadataFile.readText()
                val metadata = jsonSerializer.decodeFromString(LyricMetadata.serializer(), content)
                val updatedMetadata = metadata.copy(last_checked = timestamp)
                val jsonString = jsonSerializer.encodeToString(LyricMetadata.serializer(), updatedMetadata)
                metadataFile.writeText(jsonString)
            } catch (e: Exception) {
                FileLogger.e("VaultManager", "Failed to update last_checked in metadata.json", e)
            }
        }
    }

    fun processNewSseFile(tempFile: File, artist: String, album: String, song: String): File? {
        val vaultDir = getSongRawVaultDir(artist, album, song)
        val changelogFile = File(vaultDir, "${sanitize(song)}_changelog.json")
        val fileHash = calculateSha256(tempFile)
        val now = System.currentTimeMillis()

        if (changelogFile.exists()) {
            val changelog = try {
                jsonSerializer.decodeFromString(Changelog.serializer(), changelogFile.readText())
            } catch (e: Exception) {
                Changelog()
            }
            if (changelog.active_version_hash == fileHash) {
                // Duplicate file, update changelog timestamp
                val updatedChangelog = changelog.copy(last_checked = now)
                changelogFile.writeText(jsonSerializer.encodeToString(Changelog.serializer(), updatedChangelog))

                // Update existing metadata.json last_checked timestamp ONLY
                val parsedVaultDir = getSongParsedVaultDir(artist, album, song)
                updateMetadataLastCheckedOnly(parsedVaultDir, now)

                tempFile.delete()
                return null // Indicates no new parsing needed
            }
        }

        // It's a new version
        var newVersionNum = 1
        if (changelogFile.exists()) {
            val changelog = try {
                jsonSerializer.decodeFromString(Changelog.serializer(), changelogFile.readText())
            } catch (e: Exception) {
                Changelog()
            }
            newVersionNum = changelog.latest_version + 1
        }
        
        val newFileName = "${sanitize(song)}_raw_v${newVersionNum}.sse"
        val destinationFile = File(vaultDir, newFileName)
        tempFile.copyTo(destinationFile, overwrite = true)
        tempFile.delete()

        val newChangelog = Changelog(
            latest_version = newVersionNum,
            active_version_hash = fileHash,
            last_updated = now,
            last_checked = now
        )
        changelogFile.writeText(jsonSerializer.encodeToString(Changelog.serializer(), newChangelog))
        
        return destinationFile
    }

    suspend fun writeMetadataJsonWithRanking(
        parsedVaultDir: File,
        metadata: VaultMetadata,
        parsedLyricsList: List<ParsedLyrics> = emptyList(),
        lastChecked: Long = System.currentTimeMillis(),
        lastUpdated: Long = System.currentTimeMillis()
    ): String? = withContext(Dispatchers.IO) {
        val key = metadata.videoId.takeIf { it.isNotBlank() } ?: "${metadata.artist}_${metadata.title}"
        metadataMutexMap.getOrPut(key) { Mutex() }.withLock {
            val lyricFileInfos = parsedLyricsList.map { lyric ->
                val fileName = "${sanitize(metadata.title)}_${sanitize(lyric.provider)}_${sanitize(lyric.type)}.${lyric.format.lowercase()}"
                val rank = LyricsRanking.getRank(lyric.provider, lyric.type)
                LyricFileInfo(
                    provider = lyric.provider,
                    type = lyric.type,
                    format = lyric.format,
                    filename = fileName,
                    rank = rank
                )
            }

            val lyricMetadata = LyricMetadata(
                videoId = metadata.videoId,
                title = metadata.title,
                artist = metadata.artist,
                album = metadata.album,
                durationSeconds = metadata.durationSeconds,
                albumArtUrl = metadata.albumArtUrl,
                yt_description = metadata.description,
                lyrics = lyricFileInfos,
                last_checked = lastChecked,
                last_updated = lastUpdated
            )

            val metadataFile = File(parsedVaultDir, "metadata.json")
            val jsonString = jsonSerializer.encodeToString(LyricMetadata.serializer(), lyricMetadata)
            metadataFile.writeText(jsonString)

            return@withLock LyricsRanking.selectActiveLyricFile(lyricFileInfos)
        }
    }

    suspend fun readMetadataJson(parsedVaultDir: File): LyricMetadata? = withContext(Dispatchers.IO) {
        val metadataFile = File(parsedVaultDir, "metadata.json")
        if (!metadataFile.exists()) return@withContext null

        val content = try { metadataFile.readText() } catch (e: Exception) { return@withContext null }
        val metadata = try { jsonSerializer.decodeFromString(LyricMetadata.serializer(), content) } catch (e: Exception) { return@withContext null }

        val key = metadata.videoId.takeIf { it.isNotBlank() } ?: "${metadata.artist}_${metadata.title}"
        metadataMutexMap.getOrPut(key) { Mutex() }.withLock {
            val freshContent = try { metadataFile.readText() } catch (e: Exception) { return@withLock null }
            try {
                jsonSerializer.decodeFromString(LyricMetadata.serializer(), freshContent)
            } catch (e: Exception) {
                FileLogger.e("VaultManager", "Error reading metadata.json", e)
                null
            }
        }
    }

    suspend fun updateLyricFileRankOverride(
        parsedVaultDir: File,
        selectedFilename: String
    ): LyricMetadata? = withContext(Dispatchers.IO) {
        val metadataFile = File(parsedVaultDir, "metadata.json")
        if (!metadataFile.exists()) return@withContext null

        val content = try { metadataFile.readText() } catch (e: Exception) { return@withContext null }
        val existing = try { jsonSerializer.decodeFromString(LyricMetadata.serializer(), content) } catch (e: Exception) { return@withContext null }

        val key = existing.videoId.takeIf { it.isNotBlank() } ?: "${existing.artist}_${existing.title}"
        metadataMutexMap.getOrPut(key) { Mutex() }.withLock {
            try {
                val freshContent = metadataFile.readText()
                val freshExisting = jsonSerializer.decodeFromString(LyricMetadata.serializer(), freshContent)
                val updatedMetadata = freshExisting.copy(
                    active_lyric_file = selectedFilename,
                    last_updated = System.currentTimeMillis()
                )
                val jsonString = jsonSerializer.encodeToString(LyricMetadata.serializer(), updatedMetadata)
                metadataFile.writeText(jsonString)
                updatedMetadata
            } catch (e: Exception) {
                FileLogger.e("VaultManager", "Error updating rank override in metadata.json", e)
                null
            }
        }
    }

    suspend fun updateLyricOffset(
        parsedVaultDir: File,
        targetFilename: String,
        newOffsetMs: Long
    ): LyricMetadata? = withContext(Dispatchers.IO) {
        val metadataFile = File(parsedVaultDir, "metadata.json")
        if (!metadataFile.exists()) return@withContext null

        val content = try { metadataFile.readText() } catch (e: Exception) { return@withContext null }
        val existing = try { jsonSerializer.decodeFromString(LyricMetadata.serializer(), content) } catch (e: Exception) { return@withContext null }

        val key = existing.videoId.takeIf { it.isNotBlank() } ?: "${existing.artist}_${existing.title}"
        metadataMutexMap.getOrPut(key) { Mutex() }.withLock {
            try {
                val freshContent = metadataFile.readText()
                val freshExisting = jsonSerializer.decodeFromString(LyricMetadata.serializer(), freshContent)
                
                val updatedLyrics = freshExisting.lyrics.map { 
                    if (it.filename == targetFilename) it.copy(offsetMs = newOffsetMs) else it
                }

                val updatedMetadata = freshExisting.copy(
                    lyrics = updatedLyrics,
                    last_updated = System.currentTimeMillis()
                )
                
                val jsonString = jsonSerializer.encodeToString(LyricMetadata.serializer(), updatedMetadata)
                metadataFile.writeText(jsonString)
                updatedMetadata
            } catch (e: Exception) {
                FileLogger.e("VaultManager", "Error updating lyric offset in metadata.json", e)
                null
            }
        }
    }

    fun writeMetadataJson(
        parsedVaultDir: File,
        metadata: VaultMetadata,
        parsedLyricsList: List<ParsedLyrics> = emptyList(),
        lastChecked: Long = System.currentTimeMillis(),
        lastUpdated: Long = System.currentTimeMillis()
    ) {
        val lyricFileInfos = parsedLyricsList.map { lyric ->
            val fileName = "${sanitize(metadata.title)}_${sanitize(lyric.provider)}_${sanitize(lyric.type)}.${lyric.format.lowercase()}"
            LyricFileInfo(
                provider = lyric.provider,
                type = lyric.type,
                format = lyric.format,
                filename = fileName,
                rank = LyricsRanking.getRank(lyric.provider, lyric.type)
            )
        }

        val lyricMetadata = LyricMetadata(
            videoId = metadata.videoId,
            title = metadata.title,
            artist = metadata.artist,
            album = metadata.album,
            durationSeconds = metadata.durationSeconds,
            albumArtUrl = metadata.albumArtUrl,
            yt_description = metadata.description,
            lyrics = lyricFileInfos,
            last_checked = lastChecked,
            last_updated = lastUpdated
        )

        val metadataFile = File(parsedVaultDir, "metadata.json")
        val jsonString = jsonSerializer.encodeToString(LyricMetadata.serializer(), lyricMetadata)
        metadataFile.writeText(jsonString)
    }

    fun writeErrorLog(module: String, error: String) {
        FileLogger.log(module, error)
    }
}

