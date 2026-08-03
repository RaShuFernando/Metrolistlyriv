package com.metrolist.music.lyrics

import com.metrolist.music.lyrics.overlay.LyricParser
import com.metrolist.music.lyrics.overlay.OverlayLyricLine
import com.metrolist.music.lyrics.overlay.OverlayLyricWord

class QrcParser : LyricParser {
    // Matches [start,duration] followed by everything up to the next [start,duration] or end of string
    private val QRC_LINE_REGEX = Regex("""\[(\d+),(\d+)\](.*?)(?=\s*\[\d+,\d+\]|$)""", RegexOption.DOT_MATCHES_ALL)
    private val LRC_LINE_REGEX = Regex("""^\[(\d{1,2}):(\d{2})\.(\d{2,3})\](.*)$""")
    
    // Matches any text (lazy evaluation) followed by (start,duration)
    private val QRC_WORD_REGEX = Regex("""(.*?)\((\d+),(\d+)\)""")

    private val AGENT_REGEX = Regex("""\{agent:([^}]+)\}""")
    private val BACKGROUND_REGEX = Regex("""^\{bg\}""")

    override fun parse(rawText: String): List<OverlayLyricLine> {
        val result = mutableListOf<OverlayLyricLine>()
        var lastNonBgAgent: String? = null
        
        val text = rawText.trim()
        val extracted = if (text.startsWith("<?xml") || text.startsWith("<QrcInfos>")) {
            val lyricContentMatch = Regex("""LyricContent="([^"]+)"""").find(text)
            lyricContentMatch?.groupValues?.get(1)
                ?.replace("&#10;", "\n")
                ?.replace("&#13;", "\r")
                ?.replace("&amp;", "&")
                ?.replace("&lt;", "<")
                ?.replace("&gt;", ">")
                ?.replace("&quot;", "\"")
                ?.replace("&apos;", "'") ?: ""
        } else {
            text
        }

        // Find all QRC line matches directly across the entire content string
        val qrcMatches = QRC_LINE_REGEX.findAll(extracted).toList()

        if (qrcMatches.isNotEmpty()) {
            qrcMatches.forEach { match ->
                val lineStartMs = match.groupValues[1].toLongOrNull() ?: 0L
                val lineDurationMs = match.groupValues[2].toLongOrNull() ?: 0L
                var content = match.groupValues[3].trim()

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
                    wordMatches.forEach { wordMatch ->
                        val wordText = wordMatch.groupValues[1]
                        // Crucial Rule: start_ms is an absolute timestamp in milliseconds.
                        val wordOffset = wordMatch.groupValues[2].toLongOrNull() ?: 0L
                        val wordDuration = wordMatch.groupValues[3].toLongOrNull() ?: 0L
                        
                        val absoluteStartTimeMs = wordOffset
                        val absoluteEndTimeMs = absoluteStartTimeMs + wordDuration
                        
                        if (wordText.isNotEmpty()) {
                            overlayWords.add(
                                OverlayLyricWord(
                                    text = wordText,
                                    startTimeMs = absoluteStartTimeMs,
                                    endTimeMs = absoluteEndTimeMs,
                                    hasTrailingSpace = false, 
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
        } else {
            // Fallback for standard LRC parsing if no QRC tags are found
            extracted.lines().forEach { line ->
                val trimmedLine = line.trim()
                if (trimmedLine.isBlank()) return@forEach
                
                val lrcMatch = LRC_LINE_REGEX.matchEntire(trimmedLine)
                if (lrcMatch != null) {
                    val minutes = lrcMatch.groupValues[1].toLongOrNull() ?: 0L
                    val seconds = lrcMatch.groupValues[2].toLongOrNull() ?: 0L
                    val fraction = lrcMatch.groupValues[3].toLongOrNull() ?: 0L
                    val millisPart = if (lrcMatch.groupValues[3].length == 3) fraction else fraction * 10
                    val lineStartMs = minutes * 60000 + seconds * 1000 + millisPart
                    val plainText = lrcMatch.groupValues[4].trimStart()
                    
                    if (plainText.isNotBlank()) {
                        result.add(
                            OverlayLyricLine(
                                text = plainText,
                                startTimeMs = lineStartMs,
                                endTimeMs = lineStartMs + 5000L, // Arbitrary duration for standard LRC
                                words = emptyList(),
                                agent = null,
                                isBackground = false
                            )
                        )
                    }
                }
            }
        }
        
        return result.sortedBy { it.startTimeMs }
    }
}
