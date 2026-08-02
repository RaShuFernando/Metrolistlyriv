package com.metrolist.music.lyrics

object QrcParser {
    // Matches [line_start, duration] or standard [mm:ss.xx]
    private val QRC_LINE_REGEX = "^\\[(\\d+),(\\d+)\\](.*)$".toRegex()
    private val LRC_LINE_REGEX = "^\\[(\\d{1,2}):(\\d{2})\\.(\\d{2,3})\\](.*)$".toRegex()
    
    // Matches Word(start, duration) or Word((start,duration))
    private val QRC_WORD_REGEX = "([^\\(]+)\\(\\(?(\\d+),(\\d+)\\)?\\)".toRegex()

    private val AGENT_REGEX = "\\{agent:([^}]+)\\}".toRegex()
    private val BACKGROUND_REGEX = "^\\{bg\\}".toRegex()

    fun parseQrcLyrics(lines: List<String>): List<LyricsEntry> {
        val result = mutableListOf<LyricsEntry>()
        var lastNonBgAgent: String? = null
        
        val rawText = lines.joinToString("\n").trim()
        val actualLines = if (rawText.startsWith("<?xml") || rawText.startsWith("<QrcInfos>")) {
            val lyricContentMatch = Regex("LyricContent=\"([^\"]+)\"").find(rawText)
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
            lines
        }

        actualLines.forEach { line ->
            val trimmedLine = line.trim()
            if (trimmedLine.isBlank()) return@forEach
            
            var lineStartMs = 0L
            var content = trimmedLine
            var matchFound = false

            val qrcMatch = QRC_LINE_REGEX.matchEntire(trimmedLine)
            if (qrcMatch != null) {
                lineStartMs = qrcMatch.groupValues[1].toLongOrNull() ?: 0L
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
                val wordTimings = mutableListOf<WordTimestamp>()
                val plainTextBuilder = StringBuilder()

                if (wordMatches.isNotEmpty()) {
                    wordMatches.forEach { match ->
                        val wordText = match.groupValues[1]
                        // Crucial Rule: start_ms is an absolute timestamp in milliseconds. Do not add line_start_ms to it.
                        val wordOffset = match.groupValues[2].toLongOrNull() ?: 0L
                        val wordDuration = match.groupValues[3].toLongOrNull() ?: 0L
                        
                        val absoluteStartTime = wordOffset / 1000.0
                        // Calculate endTimeMs = start_ms + duration
                        val absoluteEndTime = absoluteStartTime + (wordDuration / 1000.0)
                        
                        wordTimings.add(
                            WordTimestamp(
                                text = wordText,
                                startTime = absoluteStartTime,
                                endTime = absoluteEndTime,
                                hasTrailingSpace = false,
                                isBackground = isBackground
                            )
                        )
                        plainTextBuilder.append(wordText)
                    }
                } else {
                    plainTextBuilder.append(content)
                }

                val plainText = plainTextBuilder.toString().trim()
                if (!isBackground && !agent.isNullOrBlank()) {
                    lastNonBgAgent = agent
                }

                result.add(
                    LyricsEntry(
                        time = lineStartMs,
                        text = plainText,
                        words = if (wordTimings.isNotEmpty()) wordTimings else null,
                        agent = if (isBackground) lastNonBgAgent ?: "bg" else agent,
                        isBackground = isBackground
                    )
                )
            }
        }
        
        return result.sorted()
    }
}
