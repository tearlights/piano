package com.gpiano.app.data

import android.content.Context
import androidx.room.Room

object GpianoDatabaseProvider {
    @Volatile
    private var instance: GpianoDatabase? = null

    fun get(context: Context): GpianoDatabase = instance ?: synchronized(this) {
        instance ?: Room.databaseBuilder(
            context.applicationContext,
            GpianoDatabase::class.java,
            "gpiano.db",
        ).addMigrations(*GpianoDatabaseMigration.ALL)
            .build()
            .also { instance = it }
    }
}
