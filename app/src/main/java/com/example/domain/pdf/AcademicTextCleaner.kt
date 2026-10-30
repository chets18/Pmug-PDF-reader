package com.example.domain.pdf

/**
 * Cleans academic text extracted from PDFs and papers:
 * - Strips repeating headers and footers (e.g. arXiv IDs, page numbers, journal names)
 * - Repairs line-break hyphenations (e.g. "aero- \n dynamic" -> "aerodynamic")
 * - Joins wrapped lines within paragraphs while preserving legitimate paragraph boundaries
 * - Normalizes math symbols and scientific abbreviations for optimal Text-To-Speech pronunciation
 */
object AcademicTextCleaner {

    // Common academic abbreviations that should not trigger false sentence breaks
    val KNOWN_ABBREVIATIONS = setOf(
        "et al.", "i.e.", "e.g.", "cf.", "etc.", "vs.", "fig.", "figs.", "eq.", "eqs.",
        "ref.", "refs.", "sec.", "ch.", "vol.", "no.", "dr.", "prof.", "dept.",
        "univ.", "approx.", "ca.", "const.", "max.", "min.", "stat."
    )

    /**
     * Cleans raw extracted text across pages.
     */
    fun cleanExtractedText(rawPages: List<String>): List<String> {
        if (rawPages.isEmpty()) return emptyList()

        // 1. Identify and remove repeating headers/footers across pages
        val cleanedPages = removeHeadersAndFooters(rawPages)

        // 2. Unify into a continuous stream, repairing hyphenation
        val combinedText = cleanedPages.joinToString("\n\n")

        // 3. Fix hyphenated line breaks (e.g. "hyper- \n sonic" -> "hypersonic")
        val dehyphenated = combinedText.replace(Regex("(\\b\\w+)-\\s*\\n\\s*(\\w+\\b)"), "$1$2")

        // 4. Normalize whitespace and clean junk symbols
        val normalized = dehyphenated
            .replace(Regex("[\\t ]+"), " ")
            .replace(Regex("\\n{3,}"), "\n\n")

        return listOf(normalized)
    }

    /**
     * Detects and removes repeating header and footer lines that occur on multiple pages.
     */
    private fun removeHeadersAndFooters(pages: List<String>): List<String> {
        if (pages.size <= 1) {
            return pages.map { cleanSinglePage(it) }
        }

        val pageLines = pages.map { it.lines().map { line -> line.trim() } }

        // Find candidate header/footer lines that occur frequently at the start or end of pages
        val candidateJunk = mutableSetOf<String>()
        val startLines = mutableMapOf<String, Int>()
        val endLines = mutableMapOf<String, Int>()

        for (lines in pageLines) {
            val nonBlank = lines.filter { it.isNotBlank() }
            if (nonBlank.isNotEmpty()) {
                val first = nonBlank.first()
                startLines[first] = (startLines[first] ?: 0) + 1
                val last = nonBlank.last()
                endLines[last] = (endLines[last] ?: 0) + 1
            }
        }

        // If a line repeats at start or end across at least 2 pages, mark as header/footer
        startLines.forEach { (line, count) ->
            if (count >= 2 || line.matches(Regex("(?i)^(page \\d+|\\d+ of \\d+|arxiv:.*)"))) {
                candidateJunk.add(line)
            }
        }
        endLines.forEach { (line, count) ->
            if (count >= 2 || line.matches(Regex("(?i)^(\\d+|page \\d+|\\d+ of \\d+)"))) {
                candidateJunk.add(line)
            }
        }

        return pageLines.map { lines ->
            lines.filter { line ->
                val trimmed = line.trim()
                trimmed !in candidateJunk &&
                        !trimmed.matches(Regex("^\\s*\\d{1,4}\\s*$")) && // standalone page numbers
                        !trimmed.matches(Regex("(?i)^\\s*(page\\s+\\d+|\\d+\\s+of\\s+\\d+)\\s*$"))
            }.joinToString("\n")
        }
    }

    private fun cleanSinglePage(pageText: String): String {
        return pageText.lines().filter { line ->
            val trimmed = line.trim()
            !trimmed.matches(Regex("^\\s*\\d{1,4}\\s*$")) &&
                    !trimmed.matches(Regex("(?i)^\\s*(page\\s+\\d+|\\d+\\s+of\\s+\\d+)\\s*$"))
        }.joinToString("\n")
    }

    /**
     * Prepares spoken text for TTS:
     * - Replaces math symbols with clear phonetics so the voice doesn't stumble or say "caret two"
     * - Expands standard scientific symbols (alpha, beta, pi, delta, etc.)
     */
    fun prepareForSpeech(sentence: String): String {
        var speech = sentence

        // Literal string replacements (fast and safe)
        val literalReplacements = mapOf(
            "M_\\infty" to "Mach infinity",
            "M∞" to "Mach infinity",
            "^2" to " squared",
            "^3" to " cubed",
            "±" to " plus or minus ",
            "≠" to " not equal to ",
            "≈" to " approximately ",
            "≤" to " less than or equal to ",
            "≥" to " greater than or equal to ",
            "√" to " square root of ",
            "∑" to " sum of ",
            "∫" to " integral of ",
            "∂" to " partial ",
            "∇" to " del ",
            "Δ" to " Delta ",
            "δ" to " delta ",
            "α" to " alpha ",
            "β" to " beta ",
            "γ" to " gamma ",
            "λ" to " lambda ",
            "μ" to " micro ",
            "π" to " pi ",
            "θ" to " theta ",
            "ω" to " omega ",
            "Ω" to " ohms ",
            "ρ" to " rho ",
            "σ" to " sigma ",
            "ε" to " epsilon ",
            "vs." to "versus",
            "e.g." to "for example,",
            "i.e." to "that is,",
            "et al." to "and colleagues,"
        )

        for ((target, replacement) in literalReplacements) {
            speech = speech.replace(target, replacement)
        }

        // Regex replacements for exponents like ^4 -> to the power of 4
        speech = speech.replace(Regex("\\^([0-9]+)"), " to the power of $1")
        speech = speech.replace(Regex("(?i)\\bMach\\s*([0-9]+(?:\\.[0-9]+)?)"), "Mach $1")

        // Clean extra spaces
        return speech.replace(Regex("\\s+"), " ").trim()
    }
}
