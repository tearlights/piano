package com.gpiano.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface FolderDao {
    @Query("SELECT * FROM folders ORDER BY name COLLATE NOCASE")
    fun observeAll(): Flow<List<Folder>>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(folder: Folder)

    @Query("DELETE FROM folders WHERE id = :id")
    suspend fun delete(id: String)
}
