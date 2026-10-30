package com.example.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface PmugDao {

    @Query("SELECT * FROM documents ORDER BY pinned DESC, lastReadAt DESC")
    fun getAllDocuments(): Flow<List<PmugDocumentEntity>>

    @Query("SELECT * FROM documents WHERE id = :id LIMIT 1")
    suspend fun getDocumentById(id: String): PmugDocumentEntity?

    @Query("SELECT * FROM documents WHERE hash = :hash LIMIT 1")
    suspend fun getDocumentByHash(hash: String): PmugDocumentEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDocument(document: PmugDocumentEntity)

    @Update
    suspend fun updateDocument(document: PmugDocumentEntity)

    @Query("UPDATE documents SET title = :newTitle WHERE id = :id")
    suspend fun renameDocument(id: String, newTitle: String)

    @Query("UPDATE documents SET pinned = :pinned WHERE id = :id")
    suspend fun setPinned(id: String, pinned: Boolean)

    @Query("UPDATE documents SET finished = :finished WHERE id = :id")
    suspend fun setFinished(id: String, finished: Boolean)

    @Query("UPDATE documents SET lastReadAt = :timestamp WHERE id = :id")
    suspend fun updateLastRead(id: String, timestamp: Long = System.currentTimeMillis())

    @Query("DELETE FROM documents WHERE id = :id")
    suspend fun deleteDocument(id: String)

    // Progress
    @Query("SELECT * FROM progress WHERE documentId = :documentId LIMIT 1")
    suspend fun getProgress(documentId: String): PmugProgressEntity?

    @Query("SELECT * FROM progress WHERE documentId = :documentId LIMIT 1")
    fun observeProgress(documentId: String): Flow<PmugProgressEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveProgress(progress: PmugProgressEntity)

    // Bookmarks
    @Query("SELECT * FROM bookmarks WHERE documentId = :documentId ORDER BY sentenceIndex ASC")
    fun getBookmarks(documentId: String): Flow<List<PmugBookmarkEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBookmark(bookmark: PmugBookmarkEntity)

    @Query("DELETE FROM bookmarks WHERE documentId = :documentId AND sentenceIndex = :sentenceIndex")
    suspend fun deleteBookmark(documentId: String, sentenceIndex: Int)
}
