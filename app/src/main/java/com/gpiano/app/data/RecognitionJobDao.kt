package com.gpiano.app.data

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface RecognitionJobDao {
    @Query("SELECT * FROM recognition_jobs ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<RecognitionJob>>

    @Query("SELECT * FROM recognition_jobs WHERE id = :id LIMIT 1")
    suspend fun find(id: String): RecognitionJob?

    @Query("SELECT * FROM recognition_jobs WHERE sourceScoreId = :scoreId ORDER BY createdAt DESC LIMIT 1")
    suspend fun latestForScore(scoreId: String): RecognitionJob?

    @Query("SELECT * FROM recognition_jobs WHERE resultStructureId = :structureId ORDER BY createdAt DESC LIMIT 1")
    suspend fun latestForResult(structureId: String): RecognitionJob?

    @Query("SELECT * FROM recognition_jobs ORDER BY createdAt")
    suspend fun getAll(): List<RecognitionJob>

    @Upsert
    suspend fun upsert(job: RecognitionJob)

    @Upsert
    suspend fun restoreAll(jobs: List<RecognitionJob>)

    @Update
    suspend fun update(job: RecognitionJob)
}
