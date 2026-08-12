package com.gpiano.app.scoreworkspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PracticeVersionCompilerTest {
    private lateinit var xml: String
    private lateinit var score: ScoreIr

    @Before
    fun loadFixture() {
        xml = requireNotNull(javaClass.classLoader?.getResourceAsStream(DEMO_MUSIC_XML)) {
            "测试 MusicXML 未加入 test resources"
        }.bufferedReader().use { it.readText() }
        score = MusicXmlScoreParser.parse(xml)
    }

    @Test
    fun rightHandVersionMutesOnlyLeftHandInsideSelectedRange() {
        val result = PracticeVersionCompiler.compile(
            xml,
            PracticeEditPlan(PracticeVersionPreset.RightHandOnly, 1, 2),
        )

        assertTrue(result.differences.isNotEmpty())
        assertTrue(result.differences.all { it.kind == PracticeDifferenceKind.Muted && it.measureIndex in 1..2 })
        assertTrue(result.score.events.filter { it.measureIndex in 1..2 && it.hand == ScoreHand.Left }.none { !it.isRest })
        assertTrue(result.score.events.any { it.measureIndex == 3 && it.hand == ScoreHand.Left && !it.isRest })
        assertTrue(result.score.events.any { it.measureIndex == 1 && it.hand == ScoreHand.Right && !it.isRest })
    }

    @Test
    fun reducedReachProducesAuditableValidatedChanges() {
        val result = PracticeVersionCompiler.compile(
            xml,
            PracticeEditPlan(
                PracticeVersionPreset.ReducedReach,
                1,
                score.measureCount,
                maxChordNotes = 2,
                maxChordSpanSemitones = 8,
                maxLeapSemitones = 7,
            ),
        )

        assertTrue(result.differences.isNotEmpty())
        assertTrue(result.differences.any { it.kind == PracticeDifferenceKind.RemovedChordTone || it.kind == PracticeDifferenceKind.OctaveShift })
        assertTrue(ScoreIrValidator.validate(result.score).isEmpty())
        assertEquals(score.measureCount, result.score.measureCount)
    }

    @Test
    fun leftHandVersionKeepsTargetHandAndScoreShape() {
        val result = PracticeVersionCompiler.compile(
            xml,
            PracticeEditPlan(PracticeVersionPreset.LeftHandOnly, 3, 5),
        )

        assertTrue(result.score.events.filter { it.measureIndex in 3..5 && it.hand == ScoreHand.Right }.none { !it.isRest })
        assertTrue(result.score.events.any { it.measureIndex == 3 && it.hand == ScoreHand.Left && !it.isRest })
        assertEquals(score.measureCount, result.score.measureCount)
    }
}
