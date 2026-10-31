package com.example.domain.pdf

import com.example.data.model.ParsedSentence

/**
 * Robust academic sentence splitter:
 * - Distinguishes period inside abbreviations (e.g., "et al.", "Fig. 1", "Eq. 2", "i.e.") from sentence terminators.
 * - Distinguishes decimals (e.g., "Mach 5.2", "p < 0.05", "3.14159") from sentence terminators.
 * - Handles quotes, parentheses, and academic section headers.
 */
object SentenceSplitter {

    fun splitIntoSentences(rawText: String, startPage: Int = 1): List<ParsedSentence> {
        val result = mutableListOf<ParsedSentence>()
        if (rawText.isBlank()) return result

        val paragraphs = rawText.split(Regex("\\n{2,}"))
        var sentenceIndex = 0

        for (paragraph in paragraphs) {
            val trimmedPara = paragraph.trim().replace(Regex("\\s+"), " ")
            if (trimmedPara.isBlank()) continue

            // Check if paragraph looks like a heading or section title
            val isHeading = isLikelyHeading(trimmedPara)
            if (isHeading) {
                val cleanSpoken = AcademicTextCleaner.prepareForSpeech(trimmedPara)
                result.add(
                    ParsedSentence(
                        id = sentenceIndex,
                        text = trimmedPara,
                        spokenText = cleanSpoken,
                        pageNumber = startPage,
                        isHeading = true,
                        estimatedWords = trimmedPara.split(" ").size
                    )
                )
                sentenceIndex++
                continue
            }

            // Split paragraph into sentences using intelligent boundaries
            val sentences = splitParagraph(trimmedPara)
            for (sentence in sentences) {
                val clean = sentence.trim()
                if (clean.length < 3 && !clean.any { it.isLetter() }) continue

                val cleanSpoken = AcademicTextCleaner.prepareForSpeech(clean)
                result.add(
                    ParsedSentence(
                        id = sentenceIndex,
                        text = clean,
                        spokenText = cleanSpoken,
                        pageNumber = startPage,
                        isHeading = false,
                        estimatedWords = clean.split(" ").filter { it.isNotBlank() }.size
                    )
                )
                sentenceIndex++
            }
        }

        return result
    }

    private fun isLikelyHeading(text: String): Boolean {
        // e.g. "1. Introduction", "II. Aerodynamic Heating", "ABSTRACT", "3.1 Governing Equations"
        if (text.length in 4..80) {
            if (text.matches(Regex("^(Chapter\\s+\\d+|Section\\s+\\d+|[0-9]+(\\.[0-9]+)*\\s+[A-Z].*|[IVXLCDM]+\\.\\s+[A-Z].*)"))) {
                return true
            }
            if (text == text.uppercase() && text.length < 40 && text.any { it.isLetter() }) {
                return true
            }
        }
        return false
    }

    private fun splitParagraph(paragraph: String): List<String> {
        val sentences = mutableListOf<String>()
        val sb = StringBuilder()
        var i = 0
        val length = paragraph.length

        while (i < length) {
            val c = paragraph[i]
            sb.append(c)

            // Look for potential sentence ending punctuation: '.', '!', '?'
            if (c == '.' || c == '!' || c == '?') {
                // Check if this punctuation is followed by whitespace and a capital letter, or is at the end
                val isAtEnd = (i == length - 1)
                val nextIsSpace = !isAtEnd && Character.isWhitespace(paragraph[i + 1])

                if (isAtEnd || nextIsSpace) {
                    val currentWord = getCurrentWord(paragraph, i)
                    val isAbbrev = isAbbreviation(currentWord)
                    val isDecimalNumber = isDecimal(paragraph, i)

                    if (!isAbbrev && !isDecimalNumber) {
                        // Might be followed by quote or closing paren, e.g. ')." '
                        var lookAhead = i + 1
                        while (lookAhead < length && (paragraph[lookAhead] == '"' || paragraph[lookAhead] == '\'' || paragraph[lookAhead] == ')' || paragraph[lookAhead] == ']')) {
                            sb.append(paragraph[lookAhead])
                            lookAhead++
                        }
                        i = lookAhead - 1

                        val candidate = sb.toString().trim()
                        if (candidate.isNotEmpty()) {
                            sentences.add(candidate)
                            sb.clear()
                        }
                    }
                }
            }
            i++
        }

        if (sb.isNotBlank()) {
            sentences.add(sb.toString().trim())
        }

        return sentences
    }

    private fun getCurrentWord(text: String, punctuationIndex: Int): String {
        var start = punctuationIndex
        while (start > 0 && !Character.isWhitespace(text[start - 1])) {
            start--
        }
        return text.substring(start, punctuationIndex + 1).lowercase()
    }

    private fun isAbbreviation(word: String): Boolean {
        val lower = word.lowercase()
        return AcademicTextCleaner.KNOWN_ABBREVIATIONS.contains(lower) ||
                lower.matches(Regex("^[a-z]\\.$")) || // single letter initials like "J. Smith"
                lower.matches(Regex("^[a-z]{1,3}\\.$")) && lower in setOf("al.", "no.", "dr.", "eq.", "fig.")
    }

    private fun isDecimal(text: String, dotIndex: Int): Boolean {
        if (dotIndex > 0 && dotIndex < text.length - 1) {
            val prev = text[dotIndex - 1]
            val next = text[dotIndex + 1]
            return prev.isDigit() && next.isDigit()
        }
        return false
    }
}

data class ParagraphChunk(
    val chunkId: Int,
    val sentences: List<com.example.data.model.ParsedSentence>,
    val combinedSpokenText: String
)

fun groupIntoParagraphChunks(sentences: List<com.example.data.model.ParsedSentence>): List<ParagraphChunk> {
    val chunks = mutableListOf<ParagraphChunk>()
    var currentChunkSentences = mutableListOf<com.example.data.model.ParsedSentence>()
    var currentChars = 0
    var chunkId = 0

    for (sentence in sentences) {
        currentChunkSentences.add(sentence)
        currentChars += sentence.spokenText.length

        if (currentChars >= 150 || sentence.isHeading || sentence == sentences.last()) {
            if (currentChars >= 600 || sentence == sentences.last() || sentence.isHeading) {
                chunks.add(ParagraphChunk(chunkId++, currentChunkSentences.toList(), currentChunkSentences.joinToString(" ") { it.spokenText }))
                currentChunkSentences.clear()
                currentChars = 0
            }
        }
    }
    if (currentChunkSentences.isNotEmpty()) {
        chunks.add(ParagraphChunk(chunkId, currentChunkSentences.toList(), currentChunkSentences.joinToString(" ") { it.spokenText }))
    }
    return chunks
}

