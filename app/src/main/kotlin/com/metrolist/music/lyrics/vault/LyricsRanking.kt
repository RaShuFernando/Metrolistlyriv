package com.metrolist.music.lyrics.vault

object LyricsRanking {

    private val PRIORITY_MAP: Map<String, Int> = mapOf(
        "better lyrics|syllable" to 1,
        "unison|syllable" to 2,
        "binilyrics|syllable" to 3,
        "binimum|syllable" to 3,
        "qq|syllable" to 4,
        "kugou|syllable" to 5,
        "binimum|word" to 6,
        "better lyrics portato|word" to 7,
        "musixmatch|word" to 8,
        "golyrics|word" to 9,
        "better lyrics|line" to 10,
        "unison|line" to 11,
        "youtube captions|line" to 12,
        "binilyrics|line" to 13,
        "binimum|line" to 13,
        "kugou|line" to 14,
        "lrclib|line" to 15,
        "better lyrics legato|line" to 16,
        "musixmatch|line" to 17,
        "youtube|unsynced" to 18,
        "unison|unsynced" to 19,
        "lrclib|unsynced" to 20
    )

    fun getRank(provider: String, type: String): Int {
        val key = "${provider.trim().lowercase()}|${type.trim().lowercase()}"
        return PRIORITY_MAP[key] ?: 99
    }

    /**
     * Determines the active lyric file filename from a list of LyricFileInfo based on highest rank (lowest rank integer).
     */
    fun selectActiveLyricFile(files: List<LyricFileInfo>): String? {
        if (files.isEmpty()) return null
        return files.minByOrNull { it.rank }?.filename
    }
}
