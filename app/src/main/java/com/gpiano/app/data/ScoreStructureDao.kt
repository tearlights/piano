package com.gpiano.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert

@Dao
interface ScoreStructureDao {
    @Query("SELECT * FROM score_structures WHERE sourceKey = :sourceKey LIMIT 1")
    suspend fun findBySourceKey(sourceKey: String): ScoreStructure?

    @Query("SELECT * FROM score_structures WHERE id = :id LIMIT 1")
    suspend fun findStructure(id: String): ScoreStructure?

    @Query("SELECT * FROM score_revisions WHERE id = :id LIMIT 1")
    suspend fun findRevision(id: String): ScoreRevision?

    @Query("SELECT * FROM score_revisions WHERE structureId = :structureId ORDER BY revisionNumber DESC")
    suspend fun revisionsNewestFirst(structureId: String): List<ScoreRevision>

    @Query("SELECT * FROM score_revisions WHERE structureId = :structureId AND parentRevisionId = :parentRevisionId ORDER BY revisionNumber DESC LIMIT 1")
    suspend fun latestChild(structureId: String, parentRevisionId: String): ScoreRevision?

    @Query("SELECT COALESCE(MAX(revisionNumber), -1) FROM score_revisions WHERE structureId = :structureId")
    suspend fun maxRevisionNumber(structureId: String): Int

    @Query("SELECT * FROM score_structures ORDER BY createdAt")
    suspend fun allStructures(): List<ScoreStructure>

    @Query("SELECT * FROM score_revisions ORDER BY structureId, revisionNumber")
    suspend fun allRevisions(): List<ScoreRevision>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertStructure(structure: ScoreStructure)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertRevision(revision: ScoreRevision)

    @Upsert
    suspend fun restoreStructures(structures: List<ScoreStructure>)

    @Upsert
    suspend fun restoreRevisions(revisions: List<ScoreRevision>)

    @Update
    suspend fun updateStructure(structure: ScoreStructure)

    @Query("UPDATE score_structures SET currentRevisionId = :revisionId, updatedAt = :updatedAt WHERE id = :structureId")
    suspend fun setCurrentRevision(structureId: String, revisionId: String, updatedAt: Long)

    @Query("DELETE FROM score_structures WHERE id = :structureId")
    suspend fun deleteStructure(structureId: String)
}
