package com.example.data.model

data class ParsedSentence(
    val id: Int,
    val text: String,
    val spokenText: String,
    val pageNumber: Int = 1,
    val isHeading: Boolean = false,
    val estimatedWords: Int = 0,
    val isBookmarked: Boolean = false,
    val note: String? = null
)
