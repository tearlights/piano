package com.gpiano.app.scoreworkspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PracticeGuidanceTest {
    private lateinit var score: ScoreIr

    @Before
    fun loadFixture() {
        val xml = requireNotNull(javaClass.classLoader?.getResourceAsStream(DEMO_MUSIC_XML)) {
            "测试 MusicXML 未加入 test resources"
        }.bufferedReader().use { it.readText() }
        score = MusicXmlScoreParser.parse(xml)
    }

    @Test
    fun analysisIsBoundedAndEveryGuidanceHasEvidenceAndActions() {
        val analysis = ScorePracticeAnalyzer.analyze(score, 1, 2)

        assertEquals(1, analysis.fromMeasure)
        assertEquals(2, analysis.toMeasure)
        assertTrue(analysis.eventCount > 0)
        assertTrue(analysis.guidance.isNotEmpty())
        assertTrue(analysis.guidance.all { it.evidence.measureIndexes.all { measure -> measure in 1..2 } })
        assertTrue(analysis.guidance.all { it.evidence.description.isNotBlank() })
        assertTrue(analysis.guidance.all { card -> card.instructions.map { it.order } == (1..card.instructions.size).toList() })
    }

    @Test
    fun detectsTupletsTiesCoordinationAndCreatesExecutablePlaybackAdvice() {
        val analysis = ScorePracticeAnalyzer.analyze(score, 1, 2)
        val topics = analysis.guidance.map(PracticeGuidance::topic).toSet()

        assertTrue(PracticeTopic.Tuplet in topics)
        assertTrue(PracticeTopic.TieAndSlur in topics)
        assertTrue(PracticeTopic.Coordination in topics)
        assertTrue(analysis.guidance.filter { it.topic != PracticeTopic.Overview }.all { it.recommendedPlayback != null })
        assertTrue(analysis.guidance.flatMap(PracticeGuidance::instructions).all { it.text.isNotBlank() })
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsAnalysisOutsideScoreRange() {
        ScorePracticeAnalyzer.analyze(score, 0, 1)
    }
}
