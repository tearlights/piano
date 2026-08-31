@file:OptIn(kotlin.ExperimentalUnsignedTypes::class, kotlin.contracts.ExperimentalContracts::class)

package com.gpiano.app.ui.screens

import alphaTab.Settings
import alphaTab.core.ecmaScript.Uint8Array
import alphaTab.importer.ScoreLoader
import alphaTab.midi.AlphaSynthMidiFileHandler
import alphaTab.midi.MidiFile
import alphaTab.midi.MidiFileGenerator
import alphaTab.midi.NoteOnEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AlphaTabMusicXmlImportTest {
    @Test
    fun `overfull imported bars are expanded before alphaTab generates playback`() {
        val bytes = requireNotNull(javaClass.classLoader?.getResourceAsStream("1785910774247300_654.musicxml"))
            .use { it.readBytes() }
        val uint8Constructor = Uint8Array::class.java.declaredConstructors.first {
            it.parameterTypes.size == 2 && it.parameterTypes[0] == ByteArray::class.java
        }.apply { isAccessible = true }
        val score = ScoreLoader.loadScoreFromBytes(
            uint8Constructor.newInstance(bytes, null) as Uint8Array,
            Settings(),
        )

        assertEquals(24.0, score.masterBars.length, 0.0)
        assertEquals(19_200.0, score.masterBars.get(5).start, 0.0)

        val normalized = AlphaTabPlaybackTimeline.normalizeOverfullMeasures(score)

        assertEquals(20, normalized)
        assertEquals(20_640.0, score.masterBars.get(5).start, 0.0)
        assertEquals(117_600.0, score.masterBars.last().start + score.masterBars.last().calculateDuration(), 0.0)

        val midiFile = MidiFile()
        val generator = MidiFileGenerator(
            score,
            Settings(),
            AlphaSynthMidiFileHandler(midiFile, true),
        )
        generator.generate()

        assertEquals(20_640.0, generator.tickLookup.masterBars.get(5).start, 0.0)
        assertEquals(117_600.0, generator.tickLookup.masterBars.last().end, 0.0)

        val attacks = mutableListOf<Triple<Double, Int, Int>>()
        for (event in midiFile.events) {
            if (event is NoteOnEvent) {
                attacks += Triple(event.tick, event.channel.toInt(), event.noteKey.toInt())
            }
        }
        val rapidRetriggers = attacks.groupBy { it.second to it.third }.flatMap { (_, notes) ->
            notes.sortedBy { it.first }.zipWithNext().mapNotNull { (first, second) ->
                (second.first - first.first).takeIf { it in 0.0..120.0 }
            }
        }
        assertTrue("generated MIDI must not rapidly retrigger the same channel/key", rapidRetriggers.isEmpty())
    }
}
