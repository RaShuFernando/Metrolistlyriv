package com.metrolist.music.lyrics.vault

import java.io.File
import java.io.StringReader
import java.io.StringWriter
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.stream.StreamResult
import javax.xml.transform.stream.StreamSource
import org.json.JSONObject

data class ParsedLyrics(
    val provider: String,
    val type: String,
    val format: String,
    val lyricsText: String,
    val rank: Int
)

class SseParser {

    // 1 is highest priority. Keys are strictly lowercase for safe matching.
    private val priorityTable = mapOf(
        "better lyrics|syllable" to 1,
        "unison|syllable" to 2,
        "binilyrics|syllable" to 3,
        "binimum|syllable" to 3, // API alias for BiniLyrics
        "qq|syllable" to 4,
        "kugou|syllable" to 5, // Undocumented Chinese provider
        "binimum|word" to 6, // Binimum Word sync
        "better lyrics portato|word" to 7,
        "musixmatch|word" to 8,
        "golyrics|word" to 9,
        "better lyrics|line" to 10,
        "unison|line" to 11,
        "youtube captions|line" to 12,
        "binilyrics|line" to 13,
        "binimum|line" to 13, // API alias for BiniLyrics
        "kugou|line" to 14,
        "lrclib|line" to 15,
        "better lyrics legato|line" to 16,
        "musixmatch|line" to 17,
        "youtube|unsynced" to 18,
        "unison|unsynced" to 19,
        "lrclib|unsynced" to 20
    )

    private fun formatForHumanReading(text: String, format: String): String {
        val trimmed = text.trim()
        val isXml = format == "ttml" || format == "qrc" || trimmed.startsWith("<tt") || trimmed.startsWith("<?xml")
        if (!isXml) return text // Leave .lrc and .elrc strictly alone

        return try {
            val factory = TransformerFactory.newInstance()
            try {
                factory.setAttribute("indent-number", 2)
            } catch (_: Exception) {}

            val transformer = factory.newTransformer()
            transformer.setOutputProperty(OutputKeys.INDENT, "yes")
            transformer.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "2")

            if (!trimmed.startsWith("<?xml")) {
                transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes")
            }

            val writer = StringWriter()
            val source = StreamSource(StringReader(text))
            val result = StreamResult(writer)
            transformer.transform(source, result)

            val formatted = writer.toString().trim()
            if (formatted.isNotEmpty()) formatted else text
        } catch (e: Exception) {
            text.replace("><", ">\n<")
        }
    }

    fun parse(sseFile: File): List<ParsedLyrics> {
        val results = mutableListOf<ParsedLyrics>()
        var isProviderEvent = false
        
        sseFile.forEachLine { line ->
            if (line.startsWith("event: provider")) {
                isProviderEvent = true
            } else if (isProviderEvent && line.startsWith("data:")) {
                isProviderEvent = false
                val jsonData = line.substringAfter("data:").trim()
                try {
                    val json = JSONObject(jsonData)
                    val provider = json.optString("provider", "Unknown")
                    val resultsObj = json.optJSONObject("results") ?: return@forEachLine
                    
                    // Strategy 1: Check for Musixmatch/LRCLib direct keys
                    if (resultsObj.has("wordByWord") && !resultsObj.isNull("wordByWord")) {
                        val wordLyrics = resultsObj.optString("wordByWord")
                        if (wordLyrics.isNotEmpty()) {
                            val rankKey = "${provider.lowercase()}|word"
                            val rank = priorityTable[rankKey] ?: 99
                            results.add(ParsedLyrics(provider, "Word", "elrc", wordLyrics, rank))
                        }
                    } 
                    
                    // Always check for standard synced lines as a baseline/fallback
                    if (resultsObj.has("synced") && !resultsObj.isNull("synced")) {
                        val lineLyrics = resultsObj.optString("synced")
                        if (lineLyrics.isNotEmpty()) {
                            val rankKey = "${provider.lowercase()}|line"
                            val rank = priorityTable[rankKey] ?: 99
                            results.add(ParsedLyrics(provider, "Line", "lrc", lineLyrics, rank))
                        }
                    }
                    
                    // Strategy 2: Check for nested JSON string
                    if (resultsObj.has("lyrics") && !resultsObj.isNull("lyrics")) {
                        var rawLyricsText = ""
                        var type = "Line"
                        var format = "lrc"
                        
                        val nestedStr = resultsObj.optString("lyrics")
                        try {
                            // Unescape the nested JSON
                            val nestedJson = JSONObject(nestedStr)
                            
                            // Extract the string regardless of what the key is named
                            rawLyricsText = nestedJson.optString("ttml", nestedJson.optString("lyrics", nestedStr))
                            
                            // INTELLIGENT CONTENT DETECTION
                            if (rawLyricsText.trim().startsWith("<tt")) {
                                format = "ttml"
                                // Check TTML timing type explicitly
                                val timingType = nestedJson.optString("timingType", "").lowercase()
                                type = if (timingType == "word" || timingType == "syllable" || rawLyricsText.contains("timing=\"Word\"") || rawLyricsText.contains("timing=\"Syllable\"")) {
                                    "Word"
                                } else {
                                    "Line"
                                }
                            } else if (rawLyricsText.trim().startsWith("<?xml") || rawLyricsText.contains("<QrcInfos>")) {
                                format = "qrc"
                                type = "Syllable"
                            } else {
                                format = "lrc"
                                type = "Line"
                            }
                            
                        } catch(e: Exception) {
                            // Fallback if it's just a regular string, not nested JSON
                            rawLyricsText = nestedStr
                            if (rawLyricsText.trim().startsWith("<tt")) {
                                format = "ttml"
                                type = if (rawLyricsText.contains("timing=\"Word\"") || rawLyricsText.contains("timing=\"Syllable\"")) "Word" else "Line"
                            } else if (rawLyricsText.trim().startsWith("<?xml")) {
                                format = "qrc"
                                type = "Syllable"
                            }
                        }
                        
                        if (rawLyricsText.isNotEmpty() && type != "Unsynced") {
                            val formattedLyrics = formatForHumanReading(rawLyricsText, format)
                            val rankKey = "${provider.lowercase()}|${type.lowercase()}"
                            val rank = priorityTable[rankKey] ?: 99
                            results.add(ParsedLyrics(provider, type, format, formattedLyrics, rank))
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            } else if (line.trim().isEmpty()) {
                isProviderEvent = false
            }
        }
        return results
    }
}
