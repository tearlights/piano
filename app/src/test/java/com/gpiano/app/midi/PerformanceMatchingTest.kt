package com.gpiano.app.midi

import com.gpiano.app.scoreworkspace.DEMO_MUSIC_XML
import com.gpiano.app.scoreworkspace.MusicXmlScoreParser
import com.gpiano.app.scoreworkspace.PlaybackPlanCompiler
import com.gpiano.app.scoreworkspace.PlaybackSelection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PerformanceMatchingTest {
    private lateinit var plan: com.gpiano.app.scoreworkspace.PlaybackPlan

    @Before
    fun loadPlan() {
        val xml = requireNotNull(javaClass.classLoader?.getResourceAsStream(DEMO_MUSIC_XML))
            .bufferedReader().use { it.readText() }
        plan = PlaybackPlanCompiler.compile(MusicXmlScoreParser.parse(xml), PlaybackSelection(1, 1))
    }

    @Test
    fun exactPerformanceMatchesPitchRhythmAndContinuity() {
        val notes = targetNotes(plan, timingOffsetMillis = 0)
        val report = PerformanceMatcher.match(plan, notes)

        assertEquals(100, report.pitchAccuracyPercent)
        assertEquals(100, report.rhythmAccuracyPercent)
        assertEquals(100, report.continuityPercent)
        assertEquals(0, report.missedCount)
        assertEquals(0, report.extraCount)
    }

    @Test
    fun reportsLateWrongMissingAndExtraWithoutSingleOpaqueScore() {
        val original = targetNotes(plan, timingOffsetMillis = 0).toMutableList()
        original[0] = original[0].copy(onsetNanos = original[0].onsetNanos + 500_000_000L)
        original[1] = original[1].copy(midiPitch = (original[1].midiPitch + 1).coerceAtMost(127))
        original.removeAt(original.lastIndex)
        original += PerformedMidiNote(999, 0, 20, 80, 9_000_000_000L, 100_000_000L)

        val report = PerformanceMatcher.match(plan, original)

        assertTrue(report.pitchAccuracyPercent < 100)
        assertTrue(report.rhythmAccuracyPercent < 100)
        assertTrue(report.matches.any { it.kind == MatchKind.RhythmLate || it.kind == MatchKind.Missing })
        assertTrue(report.matches.any { it.kind == MatchKind.WrongPitch })
        assertTrue(report.matches.any { it.kind == MatchKind.Missing })
        assertTrue(report.matches.any { it.kind == MatchKind.Extra })
    }

    @Test
    fun fixedTimelineKeepsCountInToFirstNoteDelay() {
        val notes = targetNotes(plan, timingOffsetMillis = 500)

        val report = PerformanceMatcher.match(plan, notes)

        assertTrue(report.matches.any { it.kind == MatchKind.RhythmLate })
    }

    private fun targetNotes(
        plan: com.gpiano.app.scoreworkspace.PlaybackPlan,
        timingOffsetMillis: Long,
    ): List<PerformedMidiNote> {
        val targets = PerformanceMatcher.expectedAttacks(plan)
        val firstTick = targets.minOf { it.startTick }
        return targets.mapIndexed { index, event ->
            val milliseconds = ((event.startTick - firstTick) * 60_000.0 /
                (plan.tempoBpm * plan.ticksPerQuarter) / plan.selection.speed).toLong() + timingOffsetMillis
            PerformedMidiNote(index, 0, event.midiPitch, 80, milliseconds * 1_000_000L, 100_000_000L)
        }
    }
}
