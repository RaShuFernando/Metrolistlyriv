package com.metrolist.music.lyrics.overlay

import java.util.regex.Pattern
import kotlin.math.max

class LrcParser : LyricParser {
    private val timePattern = Pattern.compile("\\[(\\d{2,}):(\\d{2})(?:\\.(\\d{2,3}))?]")

    override fun parse(rawText: String): List<OverlayLyricLine> {
        val lines = rawText.split("\n", "\r\n")
        val parsedLines = mutableListOf<OverlayLyricLine>()

        for (line in lines) {
            val matcher = timePattern.matcher(line)
            val timeTags = mutableListOf<Long>()
            var lastEnd = 0

            while (matcher.find()) {
                val min = matcher.group(1)?.toLong() ?: 0L
                val sec = matcher.group(2)?.toLong() ?: 0L
                val msStr = matcher.group(3)
                var ms = 0L
                if (msStr != null) {
                    ms = if (msStr.length == 2) msStr.toLong() * 10 else msStr.toLong()
                }

                val timeMs = min * 60000 + sec * 1000 + ms
                timeTags.add(timeMs)
                lastEnd = max(lastEnd, matcher.end())
            }

            if (timeTags.isNotEmpty()) {
                val text = line.substring(lastEnd).trim()
                for (timeMs in timeTags) {
                    parsedLines.add(
                        OverlayLyricLine(
                            text = text,
                            startTimeMs = timeMs,
                            endTimeMs = timeMs + 10_000L, // Default duration, will be adjusted below
                            words = emptyList(), // Standard LRC doesn't have word-level sync
                            agent = null,
                            isBackground = false
                        )
                    )
                }
            }
        }

        parsedLines.sortBy { it.startTimeMs }

        // Adjust endTimeMs based on the next line's startTimeMs
        for (i in 0 until parsedLines.size - 1) {
            val current = parsedLines[i]
            val next = parsedLines[i + 1]
            if (current.endTimeMs > next.startTimeMs) {
                parsedLines[i] = current.copy(endTimeMs = next.startTimeMs)
            }
        }

        return parsedLines
    }
}
