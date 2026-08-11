package com.metrolist.music.lyrics.overlay

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader

class BinimumParser : LyricParser {

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
        val agentMap = mutableMapOf<String, String>()
        val transliterationMap = mutableMapOf<String, MutableList<OverlayLyricWord>>()
        val translationMap = mutableMapOf<String, String>()

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
                val agent: String? = null,
                val id: String? = null,
                val type: String? = null,
                val forAttr: String? = null,
                val itunesKey: String? = null
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
                        
                        // Parse ttm:agent or agent attribute, removing # if it exists
                        var agentRef = parser.getAttributeValue(null, "ttm:agent") ?: parser.getAttributeValue(null, "agent")
                        if (agentRef != null && agentRef.startsWith("#")) {
                            agentRef = agentRef.substring(1)
                        }

                        val id = parser.getAttributeValue(null, "xml:id") ?: parser.getAttributeValue(null, "id")
                        val type = parser.getAttributeValue(null, "type")
                        val forAttr = parser.getAttributeValue(null, "for")
                        val itunesKey = parser.getAttributeValue(null, "itunes:key")

                        val isBg = (parent?.isBackground == true) || (role == "x-bg")
                        val isWord = (cleanName == "span") && (beginStr != null || endStr != null)

                        val state = TagState(
                            name = cleanName,
                            isBackground = isBg,
                            startTimeMs = parseTime(beginStr),
                            endTimeMs = parseTime(endStr),
                            isWord = isWord,
                            agent = agentRef ?: parent?.agent,
                            id = id,
                            type = type,
                            forAttr = forAttr,
                            itunesKey = itunesKey
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
                            if (stack.last().name == cleanName) {
                                val state = stack.removeAt(stack.size - 1)
                                
                                val rawTextStr = state.text.toString()
                                val trimmedText = rawTextStr.trim()
                                
                                // Map agents if defined in metadata
                                if (cleanName == "agent") {
                                    val agentId = state.id
                                    if (agentId != null) {
                                        val agentType = state.type
                                        val agentName = trimmedText
                                        val finalName = if (agentType.equals("group", ignoreCase = true)) {
                                            "$agentName (Group)"
                                        } else {
                                            agentName
                                        }
                                        agentMap[agentId] = finalName
                                    }
                                }
                                
                                if (state.name == "text") {
                                    val parent = stack.lastOrNull()
                                    val forAttr = state.forAttr
                                    if (parent != null && forAttr != null) {
                                        if (parent.name == "translation") {
                                            translationMap[forAttr] = trimmedText
                                        } else if (parent.name == "transliteration") {
                                            val fixedWords = state.words.mapIndexed { index, word ->
                                                word.copy(hasTrailingSpace = index < state.words.size - 1)
                                            }
                                            transliterationMap[forAttr] = fixedWords.toMutableList()
                                        }
                                    }
                                }

                                if (state.name == "p") {
                                    if (trimmedText.isNotEmpty() || state.words.isNotEmpty()) {
                                        val pKey = state.itunesKey ?: state.id
                                        
                                        // For Binimum, fix word spacing by defaulting hasTrailingSpace = true for all words except the last one in a <p>
                                        val fixedWords = state.words.mapIndexed { index, word ->
                                            val wordTransliterated = if (pKey != null) {
                                                val romanizedWords = transliterationMap[pKey]
                                                val match = romanizedWords?.find { Math.abs(it.startTimeMs - word.startTimeMs) < 100 }
                                                match?.text
                                            } else null
                                            word.copy(hasTrailingSpace = index < state.words.size - 1, romanizedText = wordTransliterated)
                                        }
                                        
                                        val translatedText = if (pKey != null) translationMap[pKey] else null
                                        val romanizedText = if (pKey != null) {
                                            val romanizedWords = transliterationMap[pKey]
                                            romanizedWords?.joinToString(separator = "") { it.text + (if (it.hasTrailingSpace) " " else "") }?.trim()
                                        } else null

                                        lines.add(
                                            OverlayLyricLine(
                                                text = rawTextStr.replace(Regex("\\s+"), " ").trim(),
                                                startTimeMs = state.startTimeMs,
                                                endTimeMs = state.endTimeMs,
                                                words = fixedWords,
                                                agent = state.agent,
                                                isBackground = state.isBackground,
                                                translatedText = translatedText,
                                                romanizedText = romanizedText
                                            )
                                        )
                                    }
                                } else if (state.isWord) {
                                    if (trimmedText.isNotEmpty()) {
                                        val word = OverlayLyricWord(
                                            text = rawTextStr.replace(Regex("\\s+"), " ").trim(),
                                            startTimeMs = state.startTimeMs,
                                            endTimeMs = state.endTimeMs,
                                            hasTrailingSpace = true, // Temporarily set, finalized when <p> ends
                                            isBackground = state.isBackground,
                                            agent = state.agent
                                        )
                                        
                                        val parentWithWords = stack.findLast { it.name == "p" || it.name == "text" }
                                        parentWithWords?.words?.add(word)
                                    }
                                }
                            } else {
                                // Fallback recovery
                                val matchIndex = stack.indexOfLast { it.name == cleanName }
                                if (matchIndex != -1) {
                                    while (stack.size > matchIndex + 1) {
                                        stack.removeAt(stack.size - 1)
                                    }
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

        // Post-processing: fill missing end times and resolve agent mappings
        for (i in lines.indices) {
            val line = lines[i]
            val nextLineStartTime = if (i < lines.size - 1) lines[i + 1].startTimeMs else line.startTimeMs + 5000L
            
            var fixedLineEndTime = line.endTimeMs
            if (fixedLineEndTime <= line.startTimeMs) {
                fixedLineEndTime = line.words.lastOrNull()?.endTimeMs?.takeIf { it > line.startTimeMs } ?: nextLineStartTime
            }
            
            val fixedWords = line.words.mapIndexed { j, word ->
                var fixedWordEndTime = word.endTimeMs
                if (fixedWordEndTime <= word.startTimeMs) {
                    fixedWordEndTime = if (j < line.words.size - 1) {
                        line.words[j + 1].startTimeMs
                    } else {
                        fixedLineEndTime
                    }
                }
                if (fixedWordEndTime <= word.startTimeMs) {
                    fixedWordEndTime = word.startTimeMs + 200L
                }

                // Resolve agent ID to Name
                val mappedWordAgent = if (word.agent != null) agentMap[word.agent] ?: word.agent else null

                word.copy(
                    endTimeMs = fixedWordEndTime,
                    agent = mappedWordAgent
                )
            }
            
            val maxWordEnd = fixedWords.maxOfOrNull { it.endTimeMs } ?: fixedLineEndTime
            if (fixedLineEndTime < maxWordEnd) {
                fixedLineEndTime = maxWordEnd
            }
            
            val desiredEndTime = nextLineStartTime.coerceAtMost(maxWordEnd + 10000L)
            if (fixedLineEndTime < desiredEndTime) {
                fixedLineEndTime = desiredEndTime
            }
            
            // Resolve agent ID to Name
            val mappedLineAgent = if (line.agent != null) agentMap[line.agent] ?: line.agent else null

            lines[i] = line.copy(
                endTimeMs = fixedLineEndTime,
                words = fixedWords,
                agent = mappedLineAgent
            )
        }

        return lines
    }
}
