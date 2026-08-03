package com.gpiano.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface BookmarkDao {
    @Query("SELECT * FROM bookmarks WHERE scoreId = :scoreId ORDER BY page")
    fun observe(scoreId: String): Flow<List<Bookmark>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(bookmark: Bookmark)

    @Query("DELETE FROM bookmarks WHERE scoreId = :scoreId AND page = :page")
    suspend fun delete(scoreId: String, page: Int)

    @Query("SELECT EXISTS(SELECT 1 FROM bookmarks WHERE scoreId = :scoreId AND page = :page)")
    suspend fun exists(scoreId: String, page: Int): Boolean
}
