package com.gpiano.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert

@Dao
interface PracticeAttemptDao {
    @Query("SELECT * FROM practice_attempts WHERE structureId = :structureId ORDER BY finishedAt DESC LIMIT :limit")
    suspend fun recentForStructure(structureId: String, limit: Int = 20): List<PracticeAttempt>

    @Query("SELECT * FROM practice_attempts WHERE id = :id LIMIT 1")
    suspend fun find(id: String): PracticeAttempt?

    @Query("SELECT * FROM practice_attempts ORDER BY structureId, finishedAt")
    suspend fun getAll(): List<PracticeAttempt>

    @Query("SELECT * FROM midi_performance_events WHERE attemptId = :attemptId ORDER BY sequence")
    suspend fun eventsForAttempt(attemptId: String): List<MidiPerformanceEvent>

    @Query("SELECT * FROM midi_performance_events ORDER BY attemptId, sequence")
    suspend fun getAllEvents(): List<MidiPerformanceEvent>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAttempt(attempt: PracticeAttempt)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertEvents(events: List<MidiPerformanceEvent>)

    @Upsert
    suspend fun restoreAttempts(attempts: List<PracticeAttempt>)

    @Upsert
    suspend fun restoreEvents(events: List<MidiPerformanceEvent>)
}
