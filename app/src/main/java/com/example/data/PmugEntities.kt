package com.example.data

import androidx.room.Entity
import androidx.room.PrimaryKey

enum class DocumentType { PDF, TEXT, MARKDOWN, DOCX }

@Entity(tableName = "documents")
data class PmugDocumentEntity(
    @PrimaryKey
    val id: String,
    val hash: String,
    val title: String,
    val path: String,
    val pages: Int,
    val addedAt: Long = System.currentTimeMillis(),
    val lastReadAt: Long = System.currentTimeMillis(),
    val pinned: Boolean = false,
    val finished: Boolean = false,
    val type: DocumentType = DocumentType.PDF
)

@Entity(tableName = "progress")
data class PmugProgressEntity(
    @PrimaryKey
    val documentId: String,
    val page: Int = 1,
    val sentenceIndex: Int = 0,
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "bookmarks")
data class PmugBookmarkEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val documentId: String,
    val sentenceIndex: Int,
    val snippet: String,
    val createdAt: Long = System.currentTimeMillis()
)
