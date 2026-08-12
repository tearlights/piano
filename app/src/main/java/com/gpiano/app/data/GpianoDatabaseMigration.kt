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

    val V6_TO_V7 = object : Migration(6, 7) {
        override fun migrate(database: SupportSQLiteDatabase) {
            database.execSQL(
                """
                CREATE TABLE IF NOT EXISTS score_structures (
                    id TEXT NOT NULL,
                    sourceScoreId TEXT,
                    sourceKey TEXT NOT NULL,
                    title TEXT NOT NULL,
                    formatVersion INTEGER NOT NULL,
                    currentRevisionId TEXT NOT NULL,
                    recognitionStatus TEXT NOT NULL,
                    recognitionConfidence REAL,
                    sourceMapRelativePath TEXT,
                    createdAt INTEGER NOT NULL,
                    updatedAt INTEGER NOT NULL,
                    PRIMARY KEY(id),
                    FOREIGN KEY(sourceScoreId) REFERENCES scores(id) ON UPDATE NO ACTION ON DELETE SET NULL
                )
                """.trimIndent(),
            )
            database.execSQL("CREATE INDEX IF NOT EXISTS index_score_structures_sourceScoreId ON score_structures(sourceScoreId)")
            database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_score_structures_sourceKey ON score_structures(sourceKey)")
            database.execSQL("CREATE INDEX IF NOT EXISTS index_score_structures_currentRevisionId ON score_structures(currentRevisionId)")
            database.execSQL(
                """
                CREATE TABLE IF NOT EXISTS score_revisions (
                    id TEXT NOT NULL,
                    structureId TEXT NOT NULL,
                    parentRevisionId TEXT,
                    revisionNumber INTEGER NOT NULL,
                    kind TEXT NOT NULL,
                    musicXmlRelativePath TEXT NOT NULL,
                    operationJson TEXT,
                    createdAt INTEGER NOT NULL,
                    PRIMARY KEY(id),
                    FOREIGN KEY(structureId) REFERENCES score_structures(id) ON UPDATE NO ACTION ON DELETE CASCADE
                )
                """.trimIndent(),
            )
            database.execSQL("CREATE INDEX IF NOT EXISTS index_score_revisions_structureId ON score_revisions(structureId)")
            database.execSQL("CREATE INDEX IF NOT EXISTS index_score_revisions_parentRevisionId ON score_revisions(parentRevisionId)")
            database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_score_revisions_structureId_revisionNumber ON score_revisions(structureId, revisionNumber)")
            database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_score_revisions_musicXmlRelativePath ON score_revisions(musicXmlRelativePath)")
        }
    }

    val V7_TO_V8 = object : Migration(7, 8) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS recognition_jobs (
                    id TEXT NOT NULL,
                    sourceScoreId TEXT NOT NULL,
                    sourcePageIndex INTEGER NOT NULL,
                    provider TEXT NOT NULL,
                    remoteJobId TEXT,
                    inputSha256 TEXT NOT NULL,
                    status TEXT NOT NULL,
                    stage TEXT NOT NULL,
                    attempt INTEGER NOT NULL,
                    resultStructureId TEXT,
                    errorCode TEXT,
                    errorMessage TEXT,
                    diagnosticsJson TEXT,
                    createdAt INTEGER NOT NULL,
                    updatedAt INTEGER NOT NULL,
                    PRIMARY KEY(id),
                    FOREIGN KEY(sourceScoreId) REFERENCES scores(id) ON UPDATE NO ACTION ON DELETE CASCADE,
                    FOREIGN KEY(resultStructureId) REFERENCES score_structures(id) ON UPDATE NO ACTION ON DELETE SET NULL
                )
                """.trimIndent(),
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS index_recognition_jobs_sourceScoreId ON recognition_jobs(sourceScoreId)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_recognition_jobs_resultStructureId ON recognition_jobs(resultStructureId)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_recognition_jobs_status ON recognition_jobs(status)")
        }
    }

    val V8_TO_V9 = object : Migration(8, 9) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS practice_versions (
                    id TEXT NOT NULL,
                    structureId TEXT NOT NULL,
                    baseRevisionId TEXT NOT NULL,
                    title TEXT NOT NULL,
                    purpose TEXT NOT NULL,
                    status TEXT NOT NULL,
                    fromMeasure INTEGER NOT NULL,
                    toMeasure INTEGER NOT NULL,
                    musicXmlRelativePath TEXT NOT NULL,
                    planJson TEXT NOT NULL,
                    differenceJson TEXT NOT NULL,
                    createdAt INTEGER NOT NULL,
                    updatedAt INTEGER NOT NULL,
                    PRIMARY KEY(id),
                    FOREIGN KEY(structureId) REFERENCES score_structures(id) ON UPDATE NO ACTION ON DELETE CASCADE,
                    FOREIGN KEY(baseRevisionId) REFERENCES score_revisions(id) ON UPDATE NO ACTION ON DELETE CASCADE
                )
                """.trimIndent(),
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS index_practice_versions_structureId ON practice_versions(structureId)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_practice_versions_baseRevisionId ON practice_versions(baseRevisionId)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_practice_versions_status ON practice_versions(status)")
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_practice_versions_musicXmlRelativePath ON practice_versions(musicXmlRelativePath)")
        }
    }

    val V9_TO_V10 = object : Migration(9, 10) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS practice_attempts (
                    id TEXT NOT NULL,
                    structureId TEXT NOT NULL,
                    sourceRevisionId TEXT NOT NULL,
                    practiceVersionId TEXT,
                    fromMeasure INTEGER NOT NULL,
                    toMeasure INTEGER NOT NULL,
                    hand TEXT NOT NULL,
                    speed REAL NOT NULL,
                    inputKind TEXT NOT NULL,
                    deviceId INTEGER,
                    deviceName TEXT NOT NULL,
                    startedAt INTEGER NOT NULL,
                    finishedAt INTEGER NOT NULL,
                    expectedCount INTEGER NOT NULL,
                    playedCount INTEGER NOT NULL,
                    pitchMatchedCount INTEGER NOT NULL,
                    rhythmMatchedCount INTEGER NOT NULL,
                    missedCount INTEGER NOT NULL,
                    extraCount INTEGER NOT NULL,
                    wrongPitchCount INTEGER NOT NULL,
                    continuityPercent INTEGER NOT NULL,
                    pitchAccuracyPercent INTEGER NOT NULL,
                    rhythmAccuracyPercent INTEGER NOT NULL,
                    resultJson TEXT NOT NULL,
                    PRIMARY KEY(id),
                    FOREIGN KEY(structureId) REFERENCES score_structures(id) ON UPDATE NO ACTION ON DELETE CASCADE,
                    FOREIGN KEY(sourceRevisionId) REFERENCES score_revisions(id) ON UPDATE NO ACTION ON DELETE CASCADE,
                    FOREIGN KEY(practiceVersionId) REFERENCES practice_versions(id) ON UPDATE NO ACTION ON DELETE SET NULL
                )
                """.trimIndent(),
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS index_practice_attempts_structureId ON practice_attempts(structureId)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_practice_attempts_sourceRevisionId ON practice_attempts(sourceRevisionId)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_practice_attempts_practiceVersionId ON practice_attempts(practiceVersionId)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_practice_attempts_finishedAt ON practice_attempts(finishedAt)")
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS midi_performance_events (
                    attemptId TEXT NOT NULL,
                    sequence INTEGER NOT NULL,
                    channel INTEGER NOT NULL,
                    midiPitch INTEGER NOT NULL,
                    velocity INTEGER NOT NULL,
                    onsetNanos INTEGER NOT NULL,
                    durationNanos INTEGER,
                    PRIMARY KEY(attemptId, sequence),
                    FOREIGN KEY(attemptId) REFERENCES practice_attempts(id) ON UPDATE NO ACTION ON DELETE CASCADE
                )
                """.trimIndent(),
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS index_midi_performance_events_attemptId ON midi_performance_events(attemptId)")
        }
    }

    val ALL = arrayOf(
        V1_TO_V2,
        V2_TO_V3,
        V3_TO_V4,
        V4_TO_V5,
        V5_TO_V6,
        V6_TO_V7,
        V7_TO_V8,
        V8_TO_V9,
        V9_TO_V10,
    )
}
