package com.metrolist.music.lyrics.overlay

interface LyricParser {
    fun parse(rawText: String): List<OverlayLyricLine>
}
