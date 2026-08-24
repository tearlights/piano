package com.gpiano.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

@Dao
interface BackupRestoreDao {
    @Query("DELETE FROM midi_performance_events") suspend fun clearMidiEvents()
    @Query("DELETE FROM practice_attempts") suspend fun clearAttempts()
    @Query("DELETE FROM practice_version_revisions") suspend fun clearPracticeVersionRevisions()
    @Query("DELETE FROM practice_versions") suspend fun clearPracticeVersions()
    @Query("DELETE FROM recognition_jobs") suspend fun clearRecognitionJobs()
    @Query("DELETE FROM score_revisions") suspend fun clearScoreRevisions()
    @Query("DELETE FROM score_structures") suspend fun clearScoreStructures()
    @Query("DELETE FROM bookmarks") suspend fun clearBookmarks()
    @Query("DELETE FROM score_pages") suspend fun clearPages()
    @Query("DELETE FROM scores") suspend fun clearScores()
    @Query("DELETE FROM folders") suspend fun clearFolders()

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertFolders(values: List<Folder>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertScores(values: List<Score>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertPages(values: List<ScorePage>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertBookmarks(values: List<Bookmark>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertStructures(values: List<ScoreStructure>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertRevisions(values: List<ScoreRevision>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertRecognitionJobs(values: List<RecognitionJob>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertPracticeVersions(values: List<PracticeVersion>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertPracticeVersionRevisions(values: List<PracticeVersionRevision>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertAttempts(values: List<PracticeAttempt>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertMidiEvents(values: List<MidiPerformanceEvent>)

    @Transaction
    suspend fun replaceWith(snapshot: GpianoBackupSnapshot) {
        clearMidiEvents()
        clearAttempts()
        clearPracticeVersionRevisions()
        clearPracticeVersions()
        clearRecognitionJobs()
        clearScoreRevisions()
        clearScoreStructures()
        clearBookmarks()
        clearPages()
        clearScores()
        clearFolders()

        if (snapshot.folders.isNotEmpty()) insertFolders(snapshot.folders)
        if (snapshot.scores.isNotEmpty()) insertScores(snapshot.scores)
        if (snapshot.pages.isNotEmpty()) insertPages(snapshot.pages)
        if (snapshot.bookmarks.isNotEmpty()) insertBookmarks(snapshot.bookmarks)
        if (snapshot.structures.isNotEmpty()) insertStructures(snapshot.structures)
        if (snapshot.revisions.isNotEmpty()) insertRevisions(snapshot.revisions)
        if (snapshot.recognitionJobs.isNotEmpty()) insertRecognitionJobs(snapshot.recognitionJobs)
        if (snapshot.practiceVersions.isNotEmpty()) insertPracticeVersions(snapshot.practiceVersions)
        if (snapshot.practiceVersionRevisions.isNotEmpty()) insertPracticeVersionRevisions(snapshot.practiceVersionRevisions)
        if (snapshot.practiceAttempts.isNotEmpty()) insertAttempts(snapshot.practiceAttempts)
        if (snapshot.midiPerformanceEvents.isNotEmpty()) insertMidiEvents(snapshot.midiPerformanceEvents)
    }
}
