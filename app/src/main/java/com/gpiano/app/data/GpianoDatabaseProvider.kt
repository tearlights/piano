package com.gpiano.app.data

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase

internal val enableForeignKeysCallback = object : RoomDatabase.Callback() {
    override fun onOpen(db: SupportSQLiteDatabase) {
        super.onOpen(db)
        db.execSQL("PRAGMA foreign_keys=ON")
    }
}

object GpianoDatabaseProvider {
    @Volatile
    private var instance: GpianoDatabase? = null

    fun get(context: Context): GpianoDatabase = instance ?: synchronized(this) {
        instance ?: Room.databaseBuilder(
            context.applicationContext,
            GpianoDatabase::class.java,
            "gpiano.db",
        ).addMigrations(*GpianoDatabaseMigration.ALL)
            .addCallback(enableForeignKeysCallback)
            .build()
            .also { instance = it }
    }
}
