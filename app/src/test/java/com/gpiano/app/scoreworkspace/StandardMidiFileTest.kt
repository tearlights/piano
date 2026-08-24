package com.gpiano.app.scoreworkspace

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StandardMidiFileTest {
    @Test
    fun writesFormatZeroHeaderTempoAndBothHands() {
        val bytes = StandardMidiFile.encode(
            plan(
                speed = 0.5,
                events = listOf(
                    event("right", ScoreHand.Right, 0, 480, 60),
                    event("left", ScoreHand.Left, 0, 960, 48),
                ),
            ),
        )

        assertArrayEquals("MThd".toByteArray(), bytes.copyOfRange(0, 4))
        assertEquals(0, unsigned16(bytes, 8))
        assertEquals(1, unsigned16(bytes, 10))
        assertEquals(960, unsigned16(bytes, 12))
        assertArrayEquals("MTrk".toByteArray(), bytes.copyOfRange(14, 18))
        assertTrue(bytes.containsSequence(byteArrayOf(0xff.toByte(), 0x51, 0x03, 0x0f, 0x42, 0x40)))
        assertTrue(bytes.containsSequence(byteArrayOf(0x90.toByte(), 60, 80)))
        assertTrue(bytes.containsSequence(byteArrayOf(0x91.toByte(), 48, 80)))
        assertTrue(bytes.containsSequence(byteArrayOf(0xff.toByte(), 0x2f, 0x00)))
    }

    @Test
    fun tiedContinuationDoesNotCreateSecondAttack() {
        val bytes = StandardMidiFile.encode(
            plan(
                events = listOf(
                    event("start", ScoreHand.Right, 0, 960, 60, tieStart = true),
                    event("stop", ScoreHand.Right, 960, 960, 60, tieStop = true),
                ),
            ),
        )

        assertEquals(1, bytes.countSequence(byteArrayOf(0x90.toByte(), 60, 80)))
        assertEquals(1, bytes.countSequence(byteArrayOf(0x80.toByte(), 60, 0)))
    }

    @Test
    fun tiedContinuationToleratesOneTickConversionRoundingOnly() {
        val rounded = StandardMidiFile.encode(
            plan(
                events = listOf(
                    event("start", ScoreHand.Right, 0, 960, 60, tieStart = true),
                    event("rounded-stop", ScoreHand.Right, 961, 960, 60, tieStop = true),
                ),
            ),
        )
        val realGap = StandardMidiFile.encode(
            plan(
                events = listOf(
                    event("start", ScoreHand.Right, 0, 960, 60, tieStart = true),
                    event("gapped-stop", ScoreHand.Right, 962, 960, 60, tieStop = true),
                ),
            ),
        )

        assertEquals(1, rounded.countSequence(byteArrayOf(0x90.toByte(), 60, 80)))
        assertEquals(2, realGap.countSequence(byteArrayOf(0x90.toByte(), 60, 80)))
    }

    private fun plan(speed: Double = 1.0, events: List<PlaybackEvent>) = PlaybackPlan(
        selection = PlaybackSelection(1, 1, speed = speed),
        ticksPerQuarter = 960,
        tempoBpm = 120.0,
        rangeStartTick = 0,
        rangeEndTick = 3_840,
        measures = listOf(PlaybackMeasure(1, 0, 3_840)),
        events = events,
    )

    private fun event(
        id: String,
        hand: ScoreHand,
        start: Long,
        duration: Long,
        pitch: Int,
        tieStart: Boolean = false,
        tieStop: Boolean = false,
    ) = PlaybackEvent(id, 1, hand, start, duration, pitch, tieStart, tieStop)

    private fun unsigned16(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xff) shl 8) or (bytes[offset + 1].toInt() and 0xff)

    private fun ByteArray.containsSequence(sequence: ByteArray): Boolean = countSequence(sequence) > 0

    private fun ByteArray.countSequence(sequence: ByteArray): Int = indices.count { start ->
        start + sequence.size <= size && sequence.indices.all { index -> this[start + index] == sequence[index] }
    }
}
