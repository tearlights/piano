package com.gpiano.app.scoreworkspace

import org.junit.Assert.assertTrue
import org.junit.Test

class ScoreIrValidatorTest {
    @Test
    fun `rejects zero duration overlap and end overflow`() {
        val base = event("first", onset = 0, duration = 4)
        val invalid = score(
            listOf(
                base,
                event("overlap", onset = 3, duration = 2),
                event("overflow", onset = Long.MAX_VALUE, duration = 1, voice = "2"),
                event("zero", onset = 0, duration = 0, voice = "3"),
            ),
        )

        val errors = ScoreIrValidator.validate(invalid)

        assertTrue(errors.any { "时长必须大于 0" in it })
        assertTrue(errors.any { "重叠" in it })
        assertTrue(errors.any { "结束位置溢出" in it })
    }

    @Test
    fun `large beat counts use long arithmetic in playback plan`() {
        val score = score(emptyList(), beats = Int.MAX_VALUE)

        val plan = PlaybackPlanCompiler.compile(score, PlaybackSelection(1, 1))

        assertTrue(plan.rangeEndTick > Int.MAX_VALUE.toLong())
    }

    private fun score(events: List<ScoreEventIr>, beats: Int = 4): ScoreIr = ScoreIr(
        title = "test",
        tempoBpm = 120.0,
        fifths = 0,
        beats = beats,
        beatType = 4,
        parts = listOf(
            ScorePartIr(
                index = 0,
                id = "P1",
                name = "Piano",
                measures = listOf(ScoreMeasureIr(1, "1", 1, beats, 4, events)),
            ),
        ),
    )

    private fun event(id: String, onset: Long, duration: Long, voice: String = "1") = ScoreEventIr(
        id = id,
        partIndex = 0,
        partId = "P1",
        partName = "Piano",
        measureIndex = 1,
        sourceMeasureNumber = "1",
        noteIndex = id.hashCode(),
        voice = voice,
        staff = 1,
        hand = ScoreHand.Right,
        onsetDivisions = onset,
        durationDivisions = duration,
        isChordTone = false,
        isRest = false,
        pitch = ScorePitch('C', 0, 4),
        noteType = "quarter",
        dots = 0,
        tupletActualNotes = null,
        tupletNormalNotes = null,
        tieStart = false,
        tieStop = false,
        slurStart = false,
        slurStop = false,
    )
}
