package com.gpiano.app.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DaoAtomicOperationsTest {
    @Test
    fun `bookmark toggle performs read and write as one dao operation`() = runBlocking {
        val dao = FakeBookmarkDao()

        dao.toggle("score", 3, 100L)
        assertTrue(dao.exists("score", 3))
        dao.toggle("score", 3, 200L)
        assertFalse(dao.exists("score", 3))
    }

    @Test
    fun `page move reads current order inside dao and swaps adjacent pages`() = runBlocking {
        val dao = FakeScorePageDao()
        dao.insertAll((0..2).map { ScorePage("score", it, it) })

        dao.move("score", displayIndex = 1, direction = -1)

        assertEquals(listOf(1, 0, 2), dao.listForScore("score").map { it.sourceIndex })
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { dao.move("score", displayIndex = 0, direction = 2) }
        }
        Unit
    }

    private class FakeBookmarkDao : BookmarkDao {
        private val values = mutableMapOf<Pair<String, Int>, Bookmark>()
        override suspend fun getAll() = values.values.toList()
        override fun observe(scoreId: String): Flow<List<Bookmark>> = flowOf(values.values.filter { it.scoreId == scoreId })
        override suspend fun insert(bookmark: Bookmark) { values[bookmark.scoreId to bookmark.page] = bookmark }
        override suspend fun restoreAll(bookmarks: List<Bookmark>) { bookmarks.forEach { insert(it) } }
        override suspend fun delete(scoreId: String, page: Int) { values.remove(scoreId to page) }
        override suspend fun deleteForScore(scoreId: String) { values.keys.removeAll { it.first == scoreId } }
        override suspend fun exists(scoreId: String, page: Int) = values.containsKey(scoreId to page)
    }

    private class FakeScorePageDao : ScorePageDao {
        private val values = mutableListOf<ScorePage>()
        override fun observe(scoreId: String): Flow<List<ScorePage>> = flowOf(runBlocking { listForScore(scoreId) })
        override suspend fun getAll() = values.toList()
        override suspend fun listForScore(scoreId: String) = values.filter { it.scoreId == scoreId }.sortedBy { it.displayIndex }
        override suspend fun find(scoreId: String, sourceIndex: Int) = values.find { it.scoreId == scoreId && it.sourceIndex == sourceIndex }
        override suspend fun insertAll(pages: List<ScorePage>) { values += pages }
        override suspend fun restoreAll(pages: List<ScorePage>) { insertAll(pages) }
        override suspend fun setDisplayIndex(scoreId: String, sourceIndex: Int, displayIndex: Int) {
            val index = values.indexOfFirst { it.scoreId == scoreId && it.sourceIndex == sourceIndex }
            values[index] = values[index].copy(displayIndex = displayIndex)
        }
        override suspend fun restoreOriginalOrder(scoreId: String) {
            values.indices.filter { values[it].scoreId == scoreId }.forEach { index ->
                values[index] = values[index].copy(displayIndex = values[index].sourceIndex)
            }
        }
        override suspend fun deleteForScore(scoreId: String) { values.removeAll { it.scoreId == scoreId } }
    }
}
