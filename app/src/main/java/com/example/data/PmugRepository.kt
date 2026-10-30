package com.example.data

import android.content.Context
import android.net.Uri
import com.example.domain.pdf.AcademicTextCleaner
import com.example.domain.pdf.PdfTextExtractor
import com.example.domain.pdf.SentenceSplitter
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

    /**
     * Imports a PDF: copies into app storage (files/documents/), extracts metadata,
     * computes SHA-256 hash, and renders thumbnail.
     */
    suspend fun prepareImport(uri: Uri): Result<PreparedPdf> = withContext(Dispatchers.IO) {
        try {
            val docsDir = File(context.filesDir, "documents").apply { mkdirs() }
            val rawFileName = getFileNameFromUri(context, uri)
            val extension = if (rawFileName.contains(".")) rawFileName.substringAfterLast(".") else "pdf"
            val cleanName = rawFileName.substringBeforeLast(".")

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
            val pageCount = ThumbnailCache.getPdfPageCount(targetFile)

            val prepared = PreparedPdf(
                id = "doc_" + UUID.randomUUID().toString().take(8),
                hash = hash,
                title = cleanName.substringBeforeLast(".").replace('_', ' ').replace('-', ' '),
                file = targetFile,
                pageCount = pageCount,
                rawPages = emptyList(),
                isExtractable = true
            )
            Result.success(prepared)
        } catch (e: Exception) {
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
            finished = false
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
        } catch (_: Exception) {}
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
    val isExtractable: Boolean
)
