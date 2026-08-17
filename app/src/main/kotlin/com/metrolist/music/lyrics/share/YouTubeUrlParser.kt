package com.metrolist.music.lyrics.share

import android.net.Uri
import java.util.regex.Pattern

object YouTubeUrlParser {
    // Matches youtube.com (watch?v=, embed/, v/, shorts/, live/) and youtu.be/ links
    private val YOUTUBE_VIDEO_ID_REGEX = Pattern.compile(
        """(?:https?:\/\/)?(?:[a-zA-Z0-9_-]+\.)?(?:youtube\.com\/(?:watch\?.*v=|embed\/|v\/|shorts\/|live\/)|youtu\.be\/)([a-zA-Z0-9_-]{11})""",
        Pattern.CASE_INSENSITIVE
    )

    /**
     * Extracts an 11-character YouTube video ID from incoming text, shared text blob, or direct URL.
     */
    fun extractVideoId(text: String?): String? {
        if (text.isNullOrBlank()) return null

        // 1. Try regex match across the full text (handles shared messages with text + URL)
        val matcher = YOUTUBE_VIDEO_ID_REGEX.matcher(text)
        if (matcher.find()) {
            return matcher.group(1)
        }

        // 2. Fallback: Parse via Uri for direct URLs or query parameter extraction
        return try {
            val trimmed = text.trim()
            val uri = Uri.parse(trimmed)
            when {
                uri.host?.contains("youtu.be", ignoreCase = true) == true -> {
                    uri.lastPathSegment?.takeIf { it.length == 11 }
                }
                uri.host?.contains("youtube.com", ignoreCase = true) == true -> {
                    uri.getQueryParameter("v")?.takeIf { it.length == 11 }
                }
                trimmed.length == 11 && trimmed.matches(Regex("^[a-zA-Z0-9_-]{11}$")) -> {
                    trimmed
                }
                else -> null
            }
        } catch (_: Exception) {
            null
        }
    }
}
