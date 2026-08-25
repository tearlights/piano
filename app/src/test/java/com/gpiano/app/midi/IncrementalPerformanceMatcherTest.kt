package com.gpiano.app.midi

import com.gpiano.app.scoreworkspace.PlaybackEvent
import com.gpiano.app.scoreworkspace.PlaybackMeasure
import com.gpiano.app.scoreworkspace.PlaybackPlan
import com.gpiano.app.scoreworkspace.PlaybackSelection
import com.gpiano.app.scoreworkspace.ScoreHand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IncrementalPerformanceMatcherTest {
    private val plan = PlaybackPlan(
        selection = PlaybackSelection(1, 1, speed = 1.0),
        ticksPerQuarter = 960,
        tempoBpm = 60.0,
        rangeStartTick = 0,
        rangeEndTick = 1_920,
        measures = listOf(PlaybackMeasure(1, 0, 1_920)),
        events = listOf(
            PlaybackEvent("first", 1, ScoreHand.Right, 0, 480, 60, false, false),
            PlaybackEvent("second", 1, ScoreHand.Right, 960, 480, 62, false, false),
        ),
    )

    @Test
    fun showsCurrentTargetThenUsesFinalMatcherClassification() {
        val initial = IncrementalPerformanceMatcher.evaluate(plan, emptyList(), 0)
        assertEquals(LiveFeedbackKind.Target, initial.notes.single().kind)
        assertEquals(60, initial.notes.single().midiPitch)

        val performed = listOf(PerformedMidiNote(0, 0, 60, 80, 0, 100_000_000))
        val afterFirst = IncrementalPerformanceMatcher.evaluate(plan, performed, 1_000)

        assertTrue(afterFirst.notes.any { it.eventId == "first" && it.kind == LiveFeedbackKind.Correct })
        assertTrue(afterFirst.notes.any { it.eventId == "second" && it.kind == LiveFeedbackKind.Target })
    }

    @Test
    fun marksOverdueTargetsMissingAndWrongNotesRed() {
        val wrong = listOf(PerformedMidiNote(0, 0, 61, 80, 0, 100_000_000))
        val feedback = IncrementalPerformanceMatcher.evaluate(plan, wrong, 2_000)

        assertTrue(feedback.notes.any { it.eventId == "first" && it.kind == LiveFeedbackKind.Incorrect })
        assertTrue(feedback.notes.any { it.eventId == "second" && it.kind == LiveFeedbackKind.Missing })
    }
}
