package com.gpiano.app.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class BackupRestoreDaoTest {
    @Test
    fun `empty snapshot still clears every table in foreign key order`() = runBlocking {
        val dao = RecordingBackupRestoreDao()

        dao.replaceWith(emptySnapshot())

        assertEquals(
            listOf(
                "clear:midi", "clear:attempts", "clear:version-revisions", "clear:versions",
                "clear:jobs", "clear:revisions", "clear:structures", "clear:bookmarks",
                "clear:pages", "clear:scores", "clear:folders",
            ),
            dao.calls,
        )
        assertFalse(dao.calls.any { it.startsWith("insert:") })
    }

    @Test
    fun `replacement inserts parents before dependent rows`() = runBlocking {
        val dao = RecordingBackupRestoreDao()
        val snapshot = emptySnapshot().copy(
            folders = listOf(Folder("folder", "练习", 1)),
            scores = listOf(Score("score", "谱", "score.pdf", "scores/score.pdf", "application/pdf", 1, folderId = "folder")),
            pages = listOf(ScorePage("score", 0, 0)),
            bookmarks = listOf(Bookmark("score", 1, 1)),
        )

        dao.replaceWith(snapshot)

        assertEquals(
            listOf("insert:folders", "insert:scores", "insert:pages", "insert:bookmarks"),
            dao.calls.filter { it.startsWith("insert:") },
        )
    }

    private fun emptySnapshot() = GpianoBackupSnapshot(
        scores = emptyList(), pages = emptyList(), bookmarks = emptyList(), folders = emptyList(),
        structures = emptyList(), revisions = emptyList(), recognitionJobs = emptyList(),
        practiceVersions = emptyList(), practiceAttempts = emptyList(), midiPerformanceEvents = emptyList(),
    )

    private class RecordingBackupRestoreDao : BackupRestoreDao {
        val calls = mutableListOf<String>()
        override suspend fun clearMidiEvents() { calls += "clear:midi" }
        override suspend fun clearAttempts() { calls += "clear:attempts" }
        override suspend fun clearPracticeVersionRevisions() { calls += "clear:version-revisions" }
        override suspend fun clearPracticeVersions() { calls += "clear:versions" }
        override suspend fun clearRecognitionJobs() { calls += "clear:jobs" }
        override suspend fun clearScoreRevisions() { calls += "clear:revisions" }
        override suspend fun clearScoreStructures() { calls += "clear:structures" }
        override suspend fun clearBookmarks() { calls += "clear:bookmarks" }
        override suspend fun clearPages() { calls += "clear:pages" }
        override suspend fun clearScores() { calls += "clear:scores" }
        override suspend fun clearFolders() { calls += "clear:folders" }
        override suspend fun insertFolders(values: List<Folder>) { calls += "insert:folders" }
        override suspend fun insertScores(values: List<Score>) { calls += "insert:scores" }
        override suspend fun insertPages(values: List<ScorePage>) { calls += "insert:pages" }
        override suspend fun insertBookmarks(values: List<Bookmark>) { calls += "insert:bookmarks" }
        override suspend fun insertStructures(values: List<ScoreStructure>) { calls += "insert:structures" }
        override suspend fun insertRevisions(values: List<ScoreRevision>) { calls += "insert:revisions" }
        override suspend fun insertRecognitionJobs(values: List<RecognitionJob>) { calls += "insert:jobs" }
        override suspend fun insertPracticeVersions(values: List<PracticeVersion>) { calls += "insert:versions" }
        override suspend fun insertPracticeVersionRevisions(values: List<PracticeVersionRevision>) { calls += "insert:version-revisions" }
        override suspend fun insertAttempts(values: List<PracticeAttempt>) { calls += "insert:attempts" }
        override suspend fun insertMidiEvents(values: List<MidiPerformanceEvent>) { calls += "insert:midi" }
    }
}
