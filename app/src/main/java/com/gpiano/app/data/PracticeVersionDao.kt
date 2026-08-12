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

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(version: PracticeVersion)

    @Upsert
    suspend fun restoreAll(versions: List<PracticeVersion>)

    @Query("UPDATE practice_versions SET status = :status, updatedAt = :updatedAt WHERE id = :id")
    suspend fun setStatus(id: String, status: String, updatedAt: Long)
}
