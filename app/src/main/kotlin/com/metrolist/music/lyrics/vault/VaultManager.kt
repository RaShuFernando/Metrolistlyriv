package com.metrolist.music.lyrics.vault

import java.io.File
import java.security.MessageDigest
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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

class VaultManager(private val masterFolderPath: String) {

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
                val json = JSONObject(metadataFile.readText())
                json.put("last_checked", timestamp)
                metadataFile.writeText(json.toString(2))
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
            val changelog = JSONObject(changelogFile.readText())
            if (changelog.optString("active_version_hash") == fileHash) {
                // Duplicate file, update changelog timestamp
                changelog.put("last_checked", now)
                changelogFile.writeText(changelog.toString())

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
            val changelog = JSONObject(changelogFile.readText())
            newVersionNum = changelog.optInt("latest_version", 0) + 1
        }
        
        val newFileName = "${sanitize(song)}_raw_v${newVersionNum}.sse"
        val destinationFile = File(vaultDir, newFileName)
        tempFile.copyTo(destinationFile, overwrite = true)
        tempFile.delete()

        val json = JSONObject().apply {
            put("latest_version", newVersionNum)
            put("active_version_hash", fileHash)
            put("last_updated", now)
            put("last_checked", now)
        }
        changelogFile.writeText(json.toString())
        
        return destinationFile
    }

    fun writeMetadataJson(
        parsedVaultDir: File,
        metadata: VaultMetadata,
        parsedLyricsList: List<ParsedLyrics> = emptyList(),
        lastChecked: Long = System.currentTimeMillis(),
        lastUpdated: Long = System.currentTimeMillis()
    ) {
        val lyricsArray = org.json.JSONArray()
        parsedLyricsList.forEach { lyric ->
            val fileName = "${sanitize(metadata.title)}_${sanitize(lyric.provider)}_${sanitize(lyric.type)}.${lyric.format.lowercase()}"
            val lyricObj = JSONObject().apply {
                put("provider", lyric.provider)
                put("type", lyric.type)
                put("format", lyric.format)
                put("file_name", fileName)
            }
            lyricsArray.put(lyricObj)
        }

        val json = JSONObject().apply {
            put("videoId", metadata.videoId)
            put("title", metadata.title)
            put("artist", metadata.artist)
            put("album", metadata.album)
            put("durationSeconds", metadata.durationSeconds)
            put("albumArtUrl", metadata.albumArtUrl)
            put("composer", metadata.composer)
            put("description", metadata.description ?: "")
            put("mediaId", metadata.mediaId)
            put("releaseYear", metadata.releaseYear ?: JSONObject.NULL)
            put("trackNumber", metadata.trackNumber ?: JSONObject.NULL)
            put("artistBrowseId", metadata.artistBrowseId ?: JSONObject.NULL)
            put("albumBrowseId", metadata.albumBrowseId ?: JSONObject.NULL)
            put("isExplicit", metadata.isExplicit)
            put("last_checked", lastChecked)
            put("last_updated", lastUpdated)
            put("lyrics", lyricsArray)
        }
        val metadataFile = File(parsedVaultDir, "metadata.json")
        metadataFile.writeText(json.toString(2))
    }

    fun writeErrorLog(module: String, error: String) {
        FileLogger.log(module, error)
    }
}
