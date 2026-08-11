package com.metrolist.music.lyrics.overlay

import com.metrolist.music.lyrics.QrcParser

object ParserFactory {
    fun getParser(filename: String): LyricParser? {
        return when {
            filename.endsWith(".qrc", ignoreCase = true) || filename.endsWith(".qrcm", ignoreCase = true) -> QrcParser()
            filename.endsWith(".ttml", ignoreCase = true) || filename.endsWith(".xml", ignoreCase = true) -> TtmlLyricParser(isGoLyrics = filename.contains("golyrics", ignoreCase = true))
            filename.endsWith(".lrc", ignoreCase = true) -> LrcParser()
            else -> null
        }
    }
}
