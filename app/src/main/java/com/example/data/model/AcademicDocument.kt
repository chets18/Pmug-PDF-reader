package com.example.data.model

data class AcademicDocument(
    val id: String,
    val title: String,
    val author: String,
    val category: String, // Aerospace, Mathematics, Physics, General
    val description: String,
    val sentences: List<ParsedSentence>,
    val retentionCards: List<RetentionCard> = emptyList(),
    val totalWords: Int = 0,
    val sourceUri: String? = null,
    val isSample: Boolean = false
) {
    val totalSentences: Int get() = sentences.size
}
