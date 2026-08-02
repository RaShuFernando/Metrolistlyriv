package com.metrolist.music.lyrics

import com.metrolist.music.lyrics.overlay.LyricParser
import com.metrolist.music.lyrics.overlay.OverlayLyricLine
import com.metrolist.music.lyrics.overlay.OverlayLyricWord

class QrcParser : LyricParser {
    // Matches [line_start, duration] or standard [mm:ss.xx]
    private val QRC_LINE_REGEX = Regex("""^\[(\d+),(\d+)\](.*)$""")
    private val LRC_LINE_REGEX = Regex("""^\[(\d{1,2}):(\d{2})\.(\d{2,3})\](.*)$""")
    
    // Matches Word(start, duration) or Word((start,duration))
    private val QRC_WORD_REGEX = Regex("""([^\(]+)\(\(?(\d+),(\d+)\)?\)""")

    private val AGENT_REGEX = Regex("""\{agent:([^}]+)\}""")
    private val BACKGROUND_REGEX = Regex("""^\{bg\}""")

    override fun parse(rawText: String): List<OverlayLyricLine> {
        val result = mutableListOf<OverlayLyricLine>()
        var lastNonBgAgent: String? = null
        
        val text = rawText.trim()
        val actualLines = if (text.startsWith("<?xml") || text.startsWith("<QrcInfos>")) {
            val lyricContentMatch = Regex("""LyricContent="([^"]+)"""").find(text)
            val extracted = lyricContentMatch?.groupValues?.get(1) ?: ""
            // Decode basic XML entities
            extracted
                .replace("&#10;", "\n")
                .replace("&#13;", "\r")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&apos;", "'")
                .lines()
        } else {
            text.lines()
        }

        actualLines.forEach { line ->
            val trimmedLine = line.trim()
            if (trimmedLine.isBlank()) return@forEach
            
            var lineStartMs = 0L
            var lineDurationMs = 0L
            var content = trimmedLine
            var matchFound = false

            val qrcMatch = QRC_LINE_REGEX.matchEntire(trimmedLine)
            if (qrcMatch != null) {
                lineStartMs = qrcMatch.groupValues[1].toLongOrNull() ?: 0L
                lineDurationMs = qrcMatch.groupValues[2].toLongOrNull() ?: 0L
                content = qrcMatch.groupValues[3].trimStart()
                matchFound = true
            } else {
                val lrcMatch = LRC_LINE_REGEX.matchEntire(trimmedLine)
                if (lrcMatch != null) {
                    val minutes = lrcMatch.groupValues[1].toLongOrNull() ?: 0L
                    val seconds = lrcMatch.groupValues[2].toLongOrNull() ?: 0L
                    val fraction = lrcMatch.groupValues[3].toLongOrNull() ?: 0L
                    val millisPart = if (lrcMatch.groupValues[3].length == 3) fraction else fraction * 10
                    lineStartMs = minutes * 60000 + seconds * 1000 + millisPart
                    content = lrcMatch.groupValues[4].trimStart()
                    matchFound = true
                }
            }

            if (matchFound) {
                // Parse agent marker {agent:v1}
                val oldAgentMatch = AGENT_REGEX.find(content)
                val agent = oldAgentMatch?.groupValues?.get(1)
                if (oldAgentMatch != null) {
                    content = content.replaceFirst(AGENT_REGEX, "")
                }

                // Parse background marker {bg}
                val isBackground = BACKGROUND_REGEX.containsMatchIn(content)
                if (isBackground) {
                    content = content.replaceFirst(BACKGROUND_REGEX, "")
                }

                val wordMatches = QRC_WORD_REGEX.findAll(content).toList()
                val overlayWords = mutableListOf<OverlayLyricWord>()
                val plainTextBuilder = StringBuilder()

                if (wordMatches.isNotEmpty()) {
                    wordMatches.forEach { match ->
                        val wordText = match.groupValues[1]
                        // Crucial Rule: start_ms is an absolute timestamp in milliseconds. Do not add line_start_ms to it.
                        val wordOffset = match.groupValues[2].toLongOrNull() ?: 0L
                        val wordDuration = match.groupValues[3].toLongOrNull() ?: 0L
                        
                        val absoluteStartTimeMs = wordOffset
                        val absoluteEndTimeMs = absoluteStartTimeMs + wordDuration
                        
                        if (wordText.isNotBlank()) {
                            overlayWords.add(
                                OverlayLyricWord(
                                    text = wordText,
                                    startTimeMs = absoluteStartTimeMs,
                                    endTimeMs = absoluteEndTimeMs,
                                    hasTrailingSpace = false, // QRC typically doesn't need this, or it includes spaces
                                    isBackground = isBackground
                                )
                            )
                        }
                        plainTextBuilder.append(wordText)
                    }
                } else {
                    plainTextBuilder.append(content)
                }

                val plainText = plainTextBuilder.toString().trim()
                if (plainText.isBlank()) return@forEach

                if (!isBackground && !agent.isNullOrBlank()) {
                    lastNonBgAgent = agent
                }

                val endTimeMs = if (overlayWords.isNotEmpty()) {
                    overlayWords.last().endTimeMs
                } else {
                    if (lineDurationMs > 0) lineStartMs + lineDurationMs else lineStartMs + 5000L
                }

                result.add(
                    OverlayLyricLine(
                        text = plainText,
                        startTimeMs = lineStartMs,
                        endTimeMs = endTimeMs,
                        words = overlayWords,
                        agent = if (isBackground) lastNonBgAgent ?: "bg" else agent,
                        isBackground = isBackground
                    )
                )
            }
        }
        
        return result.sortedBy { it.startTimeMs }
    }
}
