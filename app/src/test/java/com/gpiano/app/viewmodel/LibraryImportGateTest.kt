package com.gpiano.app.viewmodel

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryImportGateTest {
    @Test
    fun onlyOneConcurrentImportCanStart() {
        val state = MutableStateFlow(false)
        val ready = CountDownLatch(8)
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(8)
        val attempts = (1..8).map {
            executor.submit<Boolean> {
                ready.countDown()
                start.await()
                beginImport(state)
            }
        }
        ready.await()
        start.countDown()

        assertEquals(1, attempts.count { it.get() })
        assertTrue(state.value)
        state.value = false
        assertFalse(state.value)
        assertTrue(beginImport(state))
        executor.shutdownNow()
    }
}
