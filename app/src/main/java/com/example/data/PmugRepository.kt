package com.example.data

import android.content.Context
import android.net.Uri
import com.example.domain.pdf.AcademicTextCleaner
import com.example.domain.pdf.PdfTextExtractor
import com.example.domain.pdf.SentenceSplitter
import com.example.domain.pdf.DocumentText
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID

class PmugRepository(private val context: Context) {

    private val db = PmugDatabase.getInstance(context)
    private val dao = db.pmugDao()

    val allDocuments: Flow<List<PmugDocumentEntity>> = dao.getAllDocuments()

    suspend fun getDocument(id: String): PmugDocumentEntity? = withContext(Dispatchers.IO) {
        dao.getDocumentById(id)
    }

    suspend fun getProgress(documentId: String): PmugProgressEntity? = withContext(Dispatchers.IO) {
        dao.getProgress(documentId)
    }

    fun observeProgress(documentId: String): Flow<PmugProgressEntity?> = dao.observeProgress(documentId)

    suspend fun saveReadingPosition(documentId: String, page: Int, sentenceIndex: Int) = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        dao.saveProgress(
            PmugProgressEntity(
                documentId = documentId,
                page = page,
                sentenceIndex = sentenceIndex,
                updatedAt = now
            )
        )
        dao.updateLastRead(documentId, now)
    }

    suspend fun setFinished(documentId: String, finished: Boolean) = withContext(Dispatchers.IO) {
        dao.setFinished(documentId, finished)
    }

    suspend fun setPinned(documentId: String, pinned: Boolean) = withContext(Dispatchers.IO) {
        dao.setPinned(documentId, pinned)
    }

    suspend fun renameDocument(documentId: String, newTitle: String) = withContext(Dispatchers.IO) {
        dao.renameDocument(documentId, newTitle.trim())
    }

    suspend fun deleteDocument(documentId: String) = withContext(Dispatchers.IO) {
        val doc = dao.getDocumentById(documentId)
        if (doc != null) {
            val file = File(doc.path)
            if (file.exists()) {
                file.delete()
            }
            File(file.parentFile, "${file.name}.text-cache.json").delete()
            File(file.parentFile, "${file.name}.text-cache").delete()
        }
        dao.deleteDocument(documentId)
    }

    // Bookmarks
    fun getBookmarks(documentId: String): Flow<List<PmugBookmarkEntity>> = dao.getBookmarks(documentId)

    suspend fun addBookmark(documentId: String, sentenceIndex: Int, snippet: String) = withContext(Dispatchers.IO) {
        dao.insertBookmark(
            PmugBookmarkEntity(
                documentId = documentId,
                sentenceIndex = sentenceIndex,
                snippet = snippet
            )
        )
    }

    suspend fun removeBookmark(documentId: String, sentenceIndex: Int) = withContext(Dispatchers.IO) {
        dao.deleteBookmark(documentId, sentenceIndex)
    }

    /** Copies and parses a supported document into app-private storage on an IO dispatcher. */
    suspend fun prepareImport(uri: Uri): Result<PreparedPdf> = withContext(Dispatchers.IO) {
        try {
            val docsDir = File(context.filesDir, "documents").apply { mkdirs() }
            val rawFileName = getFileNameFromUri(context, uri)
            val mimeType = context.contentResolver.getType(uri).orEmpty()
            val extension = if (rawFileName.contains(".")) rawFileName.substringAfterLast(".") else when (mimeType) {
                "text/plain" -> "txt"
                "text/markdown" -> "md"
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document" -> "docx"
                "application/pdf" -> "pdf"
                else -> ""
            }
            val cleanName = rawFileName.substringBeforeLast(".")
            val type = when (extension.lowercase()) {
                "pdf" -> DocumentType.PDF
                "txt" -> DocumentType.TEXT
                "md", "markdown" -> DocumentType.MARKDOWN
                "docx" -> DocumentType.DOCX
                "doc" -> error("Legacy .doc files are not supported. Save as .docx and retry.")
                else -> error("Unsupported file type. Choose a PDF, TXT, Markdown, or DOCX file.")
            }

            val tempFile = File.createTempFile("pmug_temp_", ".$extension", context.cacheDir)
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(tempFile).use { output ->
                    input.copyTo(output)
                }
            } ?: return@withContext Result.failure(Exception("Could not read file from storage"))

            val hash = calculateSha256(tempFile)
            val targetFile = File(docsDir, "$hash.$extension")
            if (!targetFile.exists()) {
                tempFile.copyTo(targetFile, overwrite = true)
            }
            tempFile.delete()

            // Count real pages
            val rawPages = when (type) {
                DocumentType.PDF -> emptyList()
                DocumentType.TEXT -> DocumentText.paginate(DocumentText.decodeText(targetFile.readBytes()))
                DocumentType.MARKDOWN -> DocumentText.paginate(DocumentText.stripMarkdown(DocumentText.decodeText(targetFile.readBytes())))
                DocumentType.DOCX -> try {
                    DocumentText.paginate(DocumentText.extractDocx(targetFile))
                } catch (failure: Throwable) {
                    throw IllegalArgumentException("Could not read this DOCX file. It may be encrypted or corrupt.", failure)
                }
            }
            val pageCount = if (type == DocumentType.PDF) {
                PdfTextExtractor.validatePdfAndGetPageCount(context, targetFile)
            } else rawPages.size

            val prepared = PreparedPdf(
                id = "doc_" + UUID.randomUUID().toString().take(8),
                hash = hash,
                title = cleanName.substringBeforeLast(".").replace('_', ' ').replace('-', ' '),
                file = targetFile,
                pageCount = pageCount,
                rawPages = emptyList(),
                isExtractable = true,
                type = type
            )
            if (rawPages.isNotEmpty()) File(targetFile.parentFile, "${targetFile.name}.text-cache").writeText(rawPages.joinToString("\u000c"))
            Result.success(prepared)
        } catch (e: Throwable) {
            Log.e("PmugTts", "Document import failed", e)
            Result.failure(e)
        }
    }

    suspend fun commitPreparedDocument(prepared: PreparedPdf): PmugDocumentEntity = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val entity = PmugDocumentEntity(
            id = prepared.id,
            hash = prepared.hash,
            title = prepared.title,
            path = prepared.file.absolutePath,
            pages = prepared.pageCount,
            addedAt = now,
            lastReadAt = now,
            pinned = false,
            finished = false,
            type = prepared.type
        )
        dao.insertDocument(entity)
        dao.saveProgress(PmugProgressEntity(documentId = prepared.id, page = 1, sentenceIndex = 0))
        entity
    }

    private fun calculateSha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { stream ->
            val buffer = ByteArray(8192)
            var bytesRead: Int
            while (stream.read(buffer).also { bytesRead = it } != -1) {
                digest.update(buffer, 0, bytesRead)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun getFileNameFromUri(context: Context, uri: Uri): String {
        var name = "Document.pdf"
        try {
            val cursor = context.contentResolver.query(uri, null, null, null, null)
            cursor?.use {
                if (it.moveToFirst()) {
                    val nameIndex = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (nameIndex != -1) {
                        name = it.getString(nameIndex) ?: name
                    }
                }
            }
        } catch (failure: Throwable) {
            Log.w("PmugReader", "Could not read document filename", failure)
        }
        return name
    }
}

data class PreparedPdf(
    val id: String,
    val hash: String,
    val title: String,
    val file: File,
    val pageCount: Int,
    val rawPages: List<String>,
    val isExtractable: Boolean,
    val type: DocumentType = DocumentType.PDF
)
