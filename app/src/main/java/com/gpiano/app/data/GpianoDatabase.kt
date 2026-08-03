package com.gpiano.app.data

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(entities = [Score::class, ScorePage::class, Bookmark::class, Folder::class], version = 6, exportSchema = true)
abstract class GpianoDatabase : RoomDatabase() {
    abstract fun scoreDao(): ScoreDao
    abstract fun scorePageDao(): ScorePageDao
    abstract fun bookmarkDao(): BookmarkDao
    abstract fun folderDao(): FolderDao
}
