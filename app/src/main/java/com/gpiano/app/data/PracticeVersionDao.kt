package com.gpiano.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert

@Dao
interface PracticeVersionDao {
    @Query("SELECT * FROM practice_versions WHERE structureId = :structureId ORDER BY createdAt DESC")
    suspend fun listForStructure(structureId: String): List<PracticeVersion>

    @Query("SELECT * FROM practice_versions WHERE id = :id LIMIT 1")
    suspend fun find(id: String): PracticeVersion?

    @Query("SELECT * FROM practice_versions ORDER BY structureId, createdAt")
    suspend fun getAll(): List<PracticeVersion>

    @Query("SELECT * FROM practice_version_revisions WHERE id = :id LIMIT 1")
    suspend fun findRevision(id: String): PracticeVersionRevision?

    @Query("SELECT * FROM practice_version_revisions WHERE practiceVersionId = :practiceVersionId ORDER BY revisionNumber DESC")
    suspend fun revisionsNewestFirst(practiceVersionId: String): List<PracticeVersionRevision>

    @Query("SELECT * FROM practice_version_revisions WHERE practiceVersionId = :practiceVersionId AND parentRevisionId IS :parentRevisionId ORDER BY revisionNumber DESC LIMIT 1")
    suspend fun latestChild(practiceVersionId: String, parentRevisionId: String?): PracticeVersionRevision?

    @Query("SELECT COALESCE(MAX(revisionNumber), 0) FROM practice_version_revisions WHERE practiceVersionId = :practiceVersionId")
    suspend fun maxRevisionNumber(practiceVersionId: String): Int

    @Query("SELECT * FROM practice_version_revisions ORDER BY practiceVersionId, revisionNumber")
    suspend fun getAllRevisions(): List<PracticeVersionRevision>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(version: PracticeVersion)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertRevision(revision: PracticeVersionRevision)

    @Upsert
    suspend fun restoreAll(versions: List<PracticeVersion>)

    @Upsert
    suspend fun restoreAllRevisions(revisions: List<PracticeVersionRevision>)

    @Query("UPDATE practice_versions SET status = :status, updatedAt = :updatedAt WHERE id = :id")
    suspend fun setStatus(id: String, status: String, updatedAt: Long)

    @Query("UPDATE practice_versions SET currentRevisionId = :revisionId, updatedAt = :updatedAt WHERE id = :id")
    suspend fun setCurrentRevision(id: String, revisionId: String?, updatedAt: Long)
}
