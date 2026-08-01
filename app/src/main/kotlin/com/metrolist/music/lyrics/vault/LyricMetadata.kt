package com.metrolist.music.lyrics.vault

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class LyricFileInfo(
    val provider: String,
    val type: String,
    val format: String,
    @SerialName("file_name") val filename: String,
    val rank: Int = 99
)

@Serializable
data class LyricMetadata(
    val videoId: String = "",
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val durationSeconds: Int = 0,
    val albumArtUrl: String = "",
    val lyrics: List<LyricFileInfo> = emptyList(),
    val last_checked: Long = 0L,
    val last_updated: Long = 0L
)
