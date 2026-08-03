package com.gpiano.app.data

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

object GpianoDatabaseMigration {
    val V1_TO_V2 = object : Migration(1, 2) {
        override fun migrate(database: SupportSQLiteDatabase) {
            database.execSQL("CREATE TABLE IF NOT EXISTS score_pages (scoreId TEXT NOT NULL, sourceIndex INTEGER NOT NULL, displayIndex INTEGER NOT NULL, PRIMARY KEY(scoreId, sourceIndex))")
        }
    }
    val V2_TO_V3 = object : Migration(2, 3) {
        override fun migrate(database: SupportSQLiteDatabase) {
            database.execSQL("ALTER TABLE scores ADD COLUMN isFavorite INTEGER NOT NULL DEFAULT 0")
        }
    }
    val V3_TO_V4 = object : Migration(3, 4) {
        override fun migrate(database: SupportSQLiteDatabase) {
            database.execSQL("CREATE TABLE IF NOT EXISTS bookmarks (scoreId TEXT NOT NULL, page INTEGER NOT NULL, createdAt INTEGER NOT NULL, PRIMARY KEY(scoreId, page))")
        }
    }
    val V4_TO_V5 = object : Migration(4, 5) {
        override fun migrate(database: SupportSQLiteDatabase) {
            database.execSQL("ALTER TABLE scores ADD COLUMN folderId TEXT")
            database.execSQL("CREATE TABLE IF NOT EXISTS folders (id TEXT NOT NULL, name TEXT NOT NULL, createdAt INTEGER NOT NULL, PRIMARY KEY(id))")
        }
    }
    val V5_TO_V6 = object : Migration(5, 6) {
        override fun migrate(database: SupportSQLiteDatabase) {
            database.execSQL("ALTER TABLE score_pages ADD COLUMN relativePath TEXT")
        }
    }
}
