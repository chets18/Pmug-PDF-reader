package com.example.domain.retention

import com.example.data.model.ParsedSentence
import com.example.data.model.RetentionCard
import com.example.data.model.RetentionType
import java.util.UUID

/**
 * Intelligent Academic Retention Engine.
 * Analyzes sentences in academic papers, textbooks, and research articles to automatically synthesize:
 * - Active recall flashcard questions
 * - Formula comprehension prompts
 * - Acronym and definition reviews
 */
object RetentionEngine {

    fun generateRetentionCards(sentences: List<ParsedSentence>, category: String): List<RetentionCard> {
        val cards = mutableListOf<RetentionCard>()

        for (sentence in sentences) {
            val text = sentence.text
            if (sentence.isHeading || text.length < 25) continue

            // 1. Detect definition or core concept patterns (e.g. "X is defined as...", "X refers to...")
            if (text.contains("defined as", ignoreCase = true) ||
                text.contains("refers to", ignoreCase = true) ||
                text.contains("is characterized by", ignoreCase = true) ||
                text.contains("known as", ignoreCase = true)
            ) {
                val conceptName = extractSubject(text)
                if (conceptName.isNotBlank()) {
                    cards.add(
                        RetentionCard(
                            id = UUID.randomUUID().toString(),
                            type = RetentionType.CONCEPT_CHECK,
                            title = "Concept: $conceptName",
                            prompt = "What characterizes or defines $conceptName in this context?",
                            answer = text,
                            sourceSentenceId = sentence.id,
                            category = category
                        )
                    )
                }
            }

            // 2. Detect formula, equation or quantitative relationship patterns
            if (text.contains("=") || text.contains("proportional to", ignoreCase = true) ||
                text.contains("equation", ignoreCase = true) || text.contains("ratio", ignoreCase = true) ||
                text.contains("Mach", ignoreCase = true) || text.contains("Reynolds", ignoreCase = true)
            ) {
                cards.add(
                    RetentionCard(
                        id = UUID.randomUUID().toString(),
                        type = RetentionType.KEY_FORMULA,
                        title = "Formula & Mathematical Relationship",
                        prompt = "How does this paper formulate the relationship in:\n\"${text.take(90)}...\"?",
                        answer = text,
                        sourceSentenceId = sentence.id,
                        category = category
                    )
                )
            }

            // 3. Detect aerospace/engineering specific applications
            if (text.contains("stagnation", ignoreCase = true) ||
                text.contains("shock wave", ignoreCase = true) ||
                text.contains("boundary layer", ignoreCase = true) ||
                text.contains("thermal protection", ignoreCase = true) ||
                text.contains("enthalpy", ignoreCase = true) ||
                text.contains("re-entry", ignoreCase = true) ||
                text.contains("lift-to-drag", ignoreCase = true)
            ) {
                cards.add(
                    RetentionCard(
                        id = UUID.randomUUID().toString(),
                        type = RetentionType.AEROSPACE_APPLICATION,
                        title = "Engineering Application",
                        prompt = "Explain the physical mechanism described here:",
                        answer = text,
                        sourceSentenceId = sentence.id,
                        category = category
                    )
                )
            }

            if (cards.size >= 12) break // Keep a crisp, focused deck for each document
        }

        // If very few were extracted automatically, generate from key paragraphs
        if (cards.isEmpty() && sentences.isNotEmpty()) {
            val keySentences = sentences.filter { !it.isHeading && it.text.length > 50 }.take(6)
            for (s in keySentences) {
                cards.add(
                    RetentionCard(
                        id = UUID.randomUUID().toString(),
                        type = RetentionType.CONCEPT_CHECK,
                        title = "Key Takeaway",
                        prompt = "What is the primary scientific assertion here?",
                        answer = s.text,
                        sourceSentenceId = s.id,
                        category = category
                    )
                )
            }
        }

        return cards
    }

    private fun extractSubject(text: String): String {
        val markers = listOf(" is defined as ", " refers to ", " is characterized by ", " known as ")
        for (m in markers) {
            val idx = text.indexOf(m, ignoreCase = true)
            if (idx > 0) {
                val candidate = text.substring(0, idx).trim().split(" ").takeLast(4).joinToString(" ")
                return candidate.trim().removePrefix("The ").removePrefix("In aerodynamics, ")
            }
        }
        return "Core Concept"
    }

    /**
     * Converts standard text into Bionic Reading spans:
     * Highlights/bolds the fixation point (first ~40% of each word) to guide eye movement and maximize reading retention.
     */
    fun splitForBionicReading(word: String): Pair<String, String> {
        if (word.length <= 2) return Pair(word, "")
        val boldLength = when (word.length) {
            3 -> 1
            4, 5 -> 2
            6, 7 -> 3
            else -> (word.length * 0.45).toInt().coerceAtLeast(2)
        }
        return Pair(word.take(boldLength), word.drop(boldLength))
    }
}
