package com.gpiano.app.midi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MidiMessageParserTest {
    @Test
    fun parsesFragmentedMessagesRunningStatusAndVelocityZeroNoteOff() {
        val parser = MidiMessageParser()
        assertTrue(parser.feed(byteArrayOf(0x90.toByte(), 60)).isEmpty())
        val first = parser.feed(byteArrayOf(100, 64, 90, 67, 0))

        assertEquals(3, first.size)
        assertEquals(60, first[0].pitch)
        assertTrue(first[0].noteOn)
        assertEquals(64, first[1].pitch)
        assertTrue(first[1].noteOn)
        assertEquals(67, first[2].pitch)
        assertFalse(first[2].noteOn)
    }

    @Test
    fun parsesExplicitNoteOffAcrossChannels() {
        val parser = MidiMessageParser()
        val messages = parser.feed(byteArrayOf(0x92.toByte(), 72, 110, 0x82.toByte(), 72, 40))

        assertEquals(2, messages.size)
        assertEquals(2, messages[0].channel)
        assertTrue(messages[0].noteOn)
        assertFalse(messages[1].noteOn)
    }
}
