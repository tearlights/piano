package com.gpiano.app.data

import androidx.sqlite.db.SupportSQLiteDatabase
import java.lang.reflect.Proxy
import org.junit.Assert.assertTrue
import org.junit.Test

class GpianoDatabaseMigrationTest {
    @Test
    fun `legacy chain adds lastOpenedAt before creating v7 tables`() {
        val statements = mutableListOf<String>()
        val database = recordingDatabase(statements)

        GpianoDatabaseMigration.V6_TO_V7.migrate(database)

        assertTrue(statements.contains("ALTER TABLE scores ADD COLUMN lastOpenedAt INTEGER"))
    }

    @Test
    fun `database open explicitly enables foreign key enforcement`() {
        val statements = mutableListOf<String>()

        enableForeignKeysCallback.onOpen(recordingDatabase(statements))

        assertTrue(statements.contains("PRAGMA foreign_keys=ON"))
    }

    private fun recordingDatabase(statements: MutableList<String>): SupportSQLiteDatabase =
        Proxy.newProxyInstance(
            SupportSQLiteDatabase::class.java.classLoader,
            arrayOf(SupportSQLiteDatabase::class.java),
        ) { _, method, arguments ->
            if (method.name == "execSQL") {
                statements += arguments?.first() as String
            }
            when (method.returnType) {
                Boolean::class.javaPrimitiveType -> false
                Int::class.javaPrimitiveType -> 0
                Long::class.javaPrimitiveType -> 0L
                else -> null
            }
        } as SupportSQLiteDatabase
}
