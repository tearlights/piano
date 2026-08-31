package com.gpiano.app.data

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [
        Score::class,
        ScorePage::class,
        Bookmark::class,
        Folder::class,
        ScoreStructure::class,
        ScoreRevision::class,
        RecognitionJob::class,
        PracticeVersion::class,
        PracticeVersionRevision::class,
        PracticeAttempt::class,
        MidiPerformanceEvent::class,
    ],
    version = 11,
    exportSchema = true,
)
abstract class GpianoDatabase : RoomDatabase() {
    abstract fun scoreDao(): ScoreDao
    abstract fun scorePageDao(): ScorePageDao
    abstract fun bookmarkDao(): BookmarkDao
    abstract fun folderDao(): FolderDao
    abstract fun scoreStructureDao(): ScoreStructureDao
    abstract fun recognitionJobDao(): RecognitionJobDao
    abstract fun practiceVersionDao(): PracticeVersionDao
    abstract fun practiceAttemptDao(): PracticeAttemptDao
    abstract fun backupRestoreDao(): BackupRestoreDao
}
