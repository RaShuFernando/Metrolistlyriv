package com.metrolist.music.lyrics.overlay

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader

class TtmlLyricParser(private val isGoLyrics: Boolean = false) : LyricParser {

    private fun parseTime(timeStr: String?): Long {
        if (timeStr.isNullOrEmpty()) return 0L
        var ms = 0L
        try {
            val parts = timeStr.split(":")
            var secondsPart = parts.last()
            var milliseconds = 0L
            if (secondsPart.contains(".")) {
                val secMs = secondsPart.split(".")
                secondsPart = secMs[0]
                var msStr = secMs[1]
                msStr = msStr.padEnd(3, '0').take(3)
                milliseconds = msStr.toLong()
            }
            val sec = secondsPart.toLong()

            when (parts.size) {
                1 -> {
                    ms = sec * 1000 + milliseconds
                }
                2 -> {
                    val min = parts[0].toLong()
                    ms = (min * 60 + sec) * 1000 + milliseconds
                }
                3 -> {
                    val hr = parts[0].toLong()
                    val min = parts[1].toLong()
                    ms = (hr * 3600 + min * 60 + sec) * 1000 + milliseconds
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return ms
    }

    override fun parse(rawText: String): List<OverlayLyricLine> {
        val lines = mutableListOf<OverlayLyricLine>()
        try {
            val parser = Xml.newPullParser()
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            parser.setInput(StringReader(rawText))

            var eventType = parser.eventType

            class TagState(
                val name: String,
                val isBackground: Boolean,
                val startTimeMs: Long,
                val endTimeMs: Long,
                val isWord: Boolean,
                val agent: String? = null
            ) {
                val text = java.lang.StringBuilder()
                val words = mutableListOf<OverlayLyricWord>()
            }

            val stack = mutableListOf<TagState>()

            while (eventType != XmlPullParser.END_DOCUMENT) {
                val name = parser.name ?: ""
                val cleanName = if (name.contains(":")) name.substringAfter(":") else name

                when (eventType) {
                    XmlPullParser.START_TAG -> {
                        val parent = stack.lastOrNull()
                        val beginStr = parser.getAttributeValue(null, "begin")
                        val endStr = parser.getAttributeValue(null, "end")
                        val role = parser.getAttributeValue(null, "ttm:role") ?: parser.getAttributeValue(null, "role")
                        val agent = parser.getAttributeValue(null, "ttm:agent") ?: parser.getAttributeValue(null, "agent")

                        val isBg = (parent?.isBackground == true) || (role == "x-bg")
                        val isWord = (cleanName == "span") && (beginStr != null || endStr != null)

                        val state = TagState(
                            name = cleanName,
                            isBackground = isBg,
                            startTimeMs = parseTime(beginStr),
                            endTimeMs = parseTime(endStr),
                            isWord = isWord,
                            agent = agent ?: parent?.agent
                        )
                        stack.add(state)

                        if (cleanName == "br") {
                            for (s in stack) {
                                s.text.append("\n")
                            }
                        }
                    }
                    XmlPullParser.TEXT -> {
                        val text = parser.text ?: ""
                        for (state in stack) {
                            state.text.append(text)
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        if (stack.isNotEmpty()) {
                            // Find if the ending tag matches the top of the stack (it should, for well-formed XML)
                            // We only pop if it matches the cleanName to be safe, but usually it does.
                            if (stack.last().name == cleanName) {
                                val state = stack.removeAt(stack.size - 1)
                                
                                val rawText = state.text.toString()
                                val trimmedText = rawText.trim()
                                
                                if (state.name == "p") {
                                    if (trimmedText.isNotEmpty() || state.words.isNotEmpty()) {
                                        lines.add(
                                            OverlayLyricLine(
                                                text = rawText.replace(Regex("\\s+"), " ").trim(),
                                                startTimeMs = state.startTimeMs,
                                                endTimeMs = state.endTimeMs,
                                                words = state.words.toList(),
                                                agent = state.agent,
                                                isBackground = state.isBackground
                                            )
                                        )
                                    }
                                } else if (state.isWord) {
                                    if (trimmedText.isNotEmpty()) {
                                        val word = OverlayLyricWord(
                                            text = rawText.replace(Regex("\\s+"), " ").trim(),
                                            startTimeMs = state.startTimeMs,
                                            endTimeMs = state.endTimeMs,
                                            hasTrailingSpace = if (isGoLyrics) true else rawText.lastOrNull()?.isWhitespace() == true,
                                            isBackground = state.isBackground,
                                            agent = state.agent
                                        )
                                        
                                        val pState = stack.findLast { it.name == "p" }
                                        pState?.words?.add(word)
                                    }
                                }
                            } else {
                                // Malformed XML edge case: try to find the matching tag and pop up to it
                                val matchIndex = stack.indexOfLast { it.name == cleanName }
                                if (matchIndex != -1) {
                                    // Found a match, pop everything above it
                                    while (stack.size > matchIndex + 1) {
                                        stack.removeAt(stack.size - 1)
                                    }
                                    // Now pop the match itself (we ignore its content since it was malformed, or we could process it)
                                    // For simplicity, we just pop and discard to recover
                                    stack.removeAt(stack.size - 1)
                                }
                            }
                        }
                    }
                }
                eventType = parser.next()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // Post-processing: fill missing end times
        for (i in lines.indices) {
            val line = lines[i]
            val nextLineStartTime = if (i < lines.size - 1) lines[i + 1].startTimeMs else line.startTimeMs + 5000L
            
            var fixedLineEndTime = line.endTimeMs
            if (fixedLineEndTime <= line.startTimeMs) {
                // If line endTime is missing, try to derive from last word
                fixedLineEndTime = line.words.lastOrNull()?.endTimeMs?.takeIf { it > line.startTimeMs } ?: nextLineStartTime
            }
            
            // To prevent the lyrics from prematurely vanishing, we ensure the line stays active
            // until the next line begins, but we cap this extension to 10 seconds after the last word.
            val maxWordEnd = line.words.maxOfOrNull { it.endTimeMs }?.takeIf { it > line.startTimeMs } ?: fixedLineEndTime
            val desiredEndTime = nextLineStartTime.coerceAtMost(maxWordEnd + 10000L)
            
            if (fixedLineEndTime < desiredEndTime) {
                fixedLineEndTime = desiredEndTime
            }
            
            // For words, ensure they have valid end times
            val fixedWords = line.words.mapIndexed { j, word ->
                var fixedWordEndTime = word.endTimeMs
                if (fixedWordEndTime <= word.startTimeMs) {
                    fixedWordEndTime = if (j < line.words.size - 1) {
                        line.words[j + 1].startTimeMs
                    } else {
                        fixedLineEndTime
                    }
                }
                // If it's still <= start, just add a minimum duration so it doesn't instantly vanish
                if (fixedWordEndTime <= word.startTimeMs) {
                    fixedWordEndTime = word.startTimeMs + 200L
                }
                word.copy(endTimeMs = fixedWordEndTime)
            }
            
            // Also ensure the line itself has a minimum duration and encompasses its words
            val maxWordEnd = fixedWords.maxOfOrNull { it.endTimeMs } ?: fixedLineEndTime
            if (fixedLineEndTime < maxWordEnd) {
                fixedLineEndTime = maxWordEnd
            }
            
            // If the next line is very far away, we don't necessarily want it to stay forever, but we ensure it doesn't vanish prematurely.
            
            lines[i] = line.copy(
                endTimeMs = fixedLineEndTime,
                words = fixedWords
            )
        }

        return lines
    }
}
