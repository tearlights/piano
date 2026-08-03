package com.gpiano.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ScoreDao {
    @Query("SELECT * FROM scores ORDER BY COALESCE(lastOpenedAt, importedAt) DESC")
    fun observeAll(): Flow<List<Score>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(score: Score)

    @Query("UPDATE scores SET lastOpenedAt = :openedAt WHERE id = :id")
    suspend fun markOpened(id: String, openedAt: Long)

    @Query("UPDATE scores SET title = :title WHERE id = :id")
    suspend fun rename(id: String, title: String)
    @Query("SELECT * FROM scores WHERE isFavorite = 1 ORDER BY COALESCE(lastOpenedAt, importedAt) DESC")
    fun observeFavorites(): Flow<List<Score>>

    @Query("UPDATE scores SET isFavorite = CASE WHEN isFavorite = 1 THEN 0 ELSE 1 END WHERE id = :id")
    suspend fun toggleFavorite(id: String)
    @Query("UPDATE scores SET folderId = :folderId WHERE id = :id")
    suspend fun setFolder(id: String, folderId: String?)
    @Query("DELETE FROM scores WHERE id = :id")
    suspend fun delete(id: String)
}
