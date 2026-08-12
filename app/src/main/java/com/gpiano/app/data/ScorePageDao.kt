package com.gpiano.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface ScorePageDao {
    @Query("SELECT * FROM score_pages WHERE scoreId = :scoreId ORDER BY displayIndex")
    fun observe(scoreId: String): Flow<List<ScorePage>>

    @Query("SELECT * FROM score_pages ORDER BY scoreId, sourceIndex")
    suspend fun getAll(): List<ScorePage>

    @Query("SELECT * FROM score_pages WHERE scoreId = :scoreId AND sourceIndex = :sourceIndex LIMIT 1")
    suspend fun find(scoreId: String, sourceIndex: Int): ScorePage?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(pages: List<ScorePage>)

    @Upsert
    suspend fun restoreAll(pages: List<ScorePage>)

    @Query("UPDATE score_pages SET displayIndex = :displayIndex WHERE scoreId = :scoreId AND sourceIndex = :sourceIndex")
    suspend fun setDisplayIndex(scoreId: String, sourceIndex: Int, displayIndex: Int)

    @Transaction
    suspend fun swap(scoreId: String, first: ScorePage, second: ScorePage) {
        setDisplayIndex(scoreId, first.sourceIndex, second.displayIndex)
        setDisplayIndex(scoreId, second.sourceIndex, first.displayIndex)
    }

    @Query("UPDATE score_pages SET displayIndex = sourceIndex WHERE scoreId = :scoreId")
    suspend fun restoreOriginalOrder(scoreId: String)

    @Query("DELETE FROM score_pages WHERE scoreId = :scoreId")
    suspend fun deleteForScore(scoreId: String)
}
