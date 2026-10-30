package com.example.data.model

data class RetentionCard(
    val id: String,
    val type: RetentionType,
    val title: String,
    val prompt: String,
    val answer: String,
    val sourceSentenceId: Int,
    val category: String,
    val userConfidence: Int = 0 // 0 = unrated, 1 = hard, 2 = medium, 3 = mastered
)

enum class RetentionType {
    KEY_FORMULA,
    CONCEPT_CHECK,
    ACRONYM_DEFINITION,
    AEROSPACE_APPLICATION
}
