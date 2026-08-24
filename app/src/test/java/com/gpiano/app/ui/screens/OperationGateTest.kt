package com.gpiano.app.ui.screens

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OperationGateTest {
    @Test
    fun `only one concurrent operation can enter and leave reopens gate`() {
        val gate = OperationGate()
        val ready = CountDownLatch(8)
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(8)
        val results = (1..8).map {
            executor.submit<Boolean> {
                ready.countDown()
                start.await()
                gate.tryEnter()
            }
        }
        ready.await()
        start.countDown()

        assertEquals(1, results.count { it.get() })
        assertTrue(gate.isEntered)
        gate.leave()
        assertFalse(gate.isEntered)
        assertTrue(gate.tryEnter())
        gate.leave()
        executor.shutdownNow()
    }
}
