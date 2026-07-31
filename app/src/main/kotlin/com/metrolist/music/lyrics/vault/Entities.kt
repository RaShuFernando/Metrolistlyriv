package com.metrolist.music.lyrics.vault

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.ForeignKey
import androidx.room.Index

@Entity(
    tableName = "artists",
    indices = [Index(value = ["name"], unique = true)]
)
data class ArtistEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val name: String,
    val youtubeArtistId: String?
)

@Entity(
    tableName = "albums",
    foreignKeys = [
        ForeignKey(
            entity = ArtistEntity::class,
            parentColumns = ["id"],
            childColumns = ["artistId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["artistId"])]
)
data class AlbumEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val name: String,
    val youtubeAlbumId: String?,
    val artistId: Int
)

@Entity(
    tableName = "songs",
    foreignKeys = [
        ForeignKey(
            entity = AlbumEntity::class,
            parentColumns = ["id"],
            childColumns = ["albumId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["albumId"])]
)
data class SongEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val title: String,
    val durationSeconds: Int,
    val youtubeVideoId: String?,
    val albumId: Int,
    val hasLyricsAvailable: Boolean,
    val lastCheckedAt: Long = 0L
)

@Entity(
    tableName = "files",
    foreignKeys = [
        ForeignKey(
            entity = SongEntity::class,
            parentColumns = ["id"],
            childColumns = ["songId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["songId"])]
)
data class FileEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val songId: Int,
    val providerName: String,
    val syncLevel: String,
    val relativePath: String
)
