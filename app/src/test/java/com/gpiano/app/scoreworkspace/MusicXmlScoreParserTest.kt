package com.gpiano.app.scoreworkspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.nio.file.Files

class MusicXmlScoreParserTest {
    private lateinit var xml: String

    @Before
    fun loadFixture() {
        xml = requireNotNull(javaClass.classLoader?.getResourceAsStream(DEMO_MUSIC_XML)) {
            "测试 MusicXML 未加入 test resources"
        }.bufferedReader().use { it.readText() }
    }

    @Test
    fun parsesRealScoreStructureAndMusicalDetails() {
        val score = MusicXmlScoreParser.parse(xml)

        assertEquals("Music21 Fragment", score.title)
        assertEquals(2, score.parts.size)
        assertEquals(24, score.measureCount)
        assertEquals(6, score.fifths)
        assertEquals(4, score.beats)
        assertEquals(4, score.beatType)

        val first = score.parts.first().measures.first().events.first()
        assertEquals("part:1/measure:1/note:1", first.id)
        assertEquals(ScoreHand.Right, first.hand)
        assertEquals(ScorePitch('F', 1, 4), first.pitch)
        assertEquals(66, first.pitch?.midi)
        assertEquals(6_720L, first.durationDivisions)
        assertEquals(3, first.tupletActualNotes)
        assertEquals(2, first.tupletNormalNotes)

        val leftHand = score.parts[1].measures.first().events.first()
        assertEquals(ScoreHand.Left, leftHand.hand)
        assertTrue(score.events.any(ScoreEventIr::isRest))
        assertTrue(score.events.any(ScoreEventIr::isChordTone))
        assertEquals(score.events.size, score.events.map(ScoreEventIr::id).toSet().size)
        assertTrue(ScoreIrValidator.validate(score).isEmpty())
    }

    @Test
    fun twoPartScoresUsePartIdentityBeforePartLocalStaffNumber() {
        val source = """
            <score-partwise version="4.0">
              <part-list>
                <score-part id="RH"><part-name>Right</part-name></score-part>
                <score-part id="LH"><part-name>Left</part-name></score-part>
              </part-list>
              <part id="RH"><measure number="1">
                <attributes><divisions>1</divisions><time><beats>1</beats><beat-type>4</beat-type></time></attributes>
                <note><pitch><step>C</step><octave>4</octave></pitch><duration>1</duration><staff>1</staff></note>
              </measure></part>
              <part id="LH"><measure number="1">
                <attributes><divisions>1</divisions><time><beats>1</beats><beat-type>4</beat-type></time></attributes>
                <note><pitch><step>C</step><octave>3</octave></pitch><duration>1</duration><staff>1</staff></note>
              </measure></part>
            </score-partwise>
        """.trimIndent()

        val score = MusicXmlScoreParser.parse(source)

        assertEquals(ScoreHand.Right, score.parts[0].measures.single().events.single().hand)
        assertEquals(ScoreHand.Left, score.parts[1].measures.single().events.single().hand)
    }

    @Test
    fun rejectsInternalEntityDeclarationsBeforeParsing() {
        val source = """
            <!DOCTYPE score-partwise [<!ENTITY secret SYSTEM "file:///private.txt">]>
            <score-partwise><part-list/></score-partwise>
        """.trimIndent()

        val error = assertThrows(IllegalArgumentException::class.java) {
            MusicXmlScoreParser.parse(source)
        }

        assertTrue(error.message.orEmpty().contains("实体声明"))
    }

    @Test
    fun externalDoctypeIsNeverFetched() {
        val malformedDtd = Files.createTempFile("gpiano-xml-", ".dtd")
        Files.write(malformedDtd, "this is not a valid DTD".toByteArray())
        try {
            val source = """
                <!DOCTYPE score-partwise SYSTEM "${malformedDtd.toUri()}">
                <score-partwise>
                  <part-list><score-part id="P1"><part-name>Piano</part-name></score-part></part-list>
                  <part id="P1"><measure number="1">
                    <attributes><divisions>1</divisions><time><beats>1</beats><beat-type>4</beat-type></time></attributes>
                    <note><rest/><duration>1</duration></note>
                  </measure></part>
                </score-partwise>
            """.trimIndent()

            assertEquals(1, MusicXmlScoreParser.parse(source).events.size)
        } finally {
            Files.deleteIfExists(malformedDtd)
        }
    }

    @Test
    fun changesOnePitchWithoutMutatingOriginalOrDroppingStructure() {
        val original = MusicXmlScoreParser.parse(xml)
        val target = original.parts.first().measures.first().events.first()
        val newPitch = requireNotNull(target.pitch).transpose(1, preferSharps = true)

        val result = MusicXmlRevisionCompiler.apply(
            xml,
            CorrectionOperation.ChangePitch(target.id, newPitch),
        )

        assertEquals(ScorePitch('G', 0, 4), result.changedEvent.pitch)
        assertEquals(ScorePitch('F', 1, 4), MusicXmlScoreParser.parse(xml).findEvent(target.id)?.pitch)
        assertEquals(original.parts.size, result.score.parts.size)
        assertEquals(original.measureCount, result.score.measureCount)
        assertEquals(original.events.size, result.score.events.size)
        assertEquals(3, result.score.findEvent(target.id)?.tupletActualNotes)
        assertEquals(2, result.score.findEvent(target.id)?.tupletNormalNotes)
        assertTrue(result.xml.contains("<step>G</step>"))
        assertFalse(result.xml.isBlank())
    }

    @Test
    fun canRemoveAndRestoreAlterNode() {
        val score = MusicXmlScoreParser.parse(xml)
        val target = score.parts.first().measures.first().events.first()

        val natural = MusicXmlRevisionCompiler.apply(
            xml,
            CorrectionOperation.ChangePitch(target.id, ScorePitch('F', 0, 4)),
        )
        assertEquals(ScorePitch('F', 0, 4), natural.changedEvent.pitch)

        val sharpAgain = MusicXmlRevisionCompiler.apply(
            natural.xml,
            CorrectionOperation.ChangePitch(target.id, ScorePitch('F', 1, 4)),
        )
        assertEquals(ScorePitch('F', 1, 4), sharpAgain.changedEvent.pitch)
    }

    @Test
    fun rejectsMissingTargetAndRestPitchChange() {
        assertThrows(IllegalStateException::class.java) {
            MusicXmlRevisionCompiler.apply(
                xml,
                CorrectionOperation.ChangePitch("missing", ScorePitch('C', 0, 4)),
            )
        }

        val rest = MusicXmlScoreParser.parse(xml).events.first(ScoreEventIr::isRest)
        assertNull(rest.pitch)
        val error = assertThrows(IllegalArgumentException::class.java) {
            MusicXmlRevisionCompiler.apply(
                xml,
                CorrectionOperation.ChangePitch(rest.id, ScorePitch('C', 0, 4)),
            )
        }
        assertNotNull(error.message)
    }

    @Test
    fun editingSessionKeepsRevisionHistoryAndCanUndo() {
        val score = MusicXmlScoreParser.parse(xml)
        val document = MusicXmlDocument(
            sourceName = DEMO_MUSIC_XML,
            xml = xml,
            score = score,
            summary = score.toSummary(),
        )
        val target = score.events.first { it.pitch != null }
        val session = ScoreEditingSession.from(document)
        val changed = session.apply(
            CorrectionOperation.ChangePitch(
                eventId = target.id,
                pitch = requireNotNull(target.pitch).transpose(1, preferSharps = true),
            ),
        )

        assertEquals(0, session.revisionNumber)
        assertFalse(session.canUndo)
        assertEquals(1, changed.revisionNumber)
        assertTrue(changed.canUndo)
        assertEquals(ScorePitch('G', 0, 4), changed.score.findEvent(target.id)?.pitch)

        val undone = changed.undo()
        assertEquals(0, undone.revisionNumber)
        assertEquals(target.pitch, undone.score.findEvent(target.id)?.pitch)
        assertEquals(xml, undone.xml)
    }

    @Test
    fun compilesSelectedMeasureToSharedPlaybackTimeline() {
        val score = MusicXmlScoreParser.parse(xml)
        val plan = PlaybackPlanCompiler.compile(
            score,
            PlaybackSelection(startMeasure = 1, endMeasure = 2, speed = 0.5),
        )

        assertEquals(1, plan.measures.first().measureIndex)
        assertEquals(2, plan.measures.last().measureIndex)
        assertEquals(0L, plan.rangeStartTick)
        assertTrue(plan.rangeEndTick > plan.rangeStartTick)
        assertTrue(plan.durationMillis > 0)
        assertTrue(plan.events.isNotEmpty())
        assertTrue(plan.events.all { it.measureIndex in 1..2 })
        assertTrue(plan.events.all { it.durationTick > 0 && it.midiPitch in 0..127 })
        assertEquals(plan.events.size, plan.events.map(PlaybackEvent::eventId).toSet().size)

        val first = plan.events.first { it.eventId == "part:1/measure:1/note:1" }
        assertEquals(0L, first.startTick)
        assertEquals(640L, first.durationTick)
        assertEquals(66, first.midiPitch)
    }

    @Test
    fun playbackHandFilterUsesScoreIrHandMapping() {
        val score = MusicXmlScoreParser.parse(xml)
        val right = PlaybackPlanCompiler.compile(
            score,
            PlaybackSelection(1, 1, hand = PlaybackHand.Right),
        )
        val left = PlaybackPlanCompiler.compile(
            score,
            PlaybackSelection(1, 1, hand = PlaybackHand.Left),
        )

        assertTrue(right.events.isNotEmpty())
        assertTrue(left.events.isNotEmpty())
        assertTrue(right.events.all { it.hand == ScoreHand.Right })
        assertTrue(left.events.all { it.hand == ScoreHand.Left })
        assertTrue(right.events.map(PlaybackEvent::eventId).toSet().intersect(left.events.map(PlaybackEvent::eventId).toSet()).isEmpty())
    }

    @Test
    fun rejectsInvalidPlaybackRange() {
        val score = MusicXmlScoreParser.parse(xml)
        assertThrows(IllegalArgumentException::class.java) {
            PlaybackPlanCompiler.compile(score, PlaybackSelection(1, score.measureCount + 1))
        }
        assertThrows(IllegalArgumentException::class.java) {
            PlaybackSelection(3, 2)
        }
    }

    @Test
    fun normalizesMiddleTieOrderingWithoutChangingItsMeaning() {
        val source = """
            <?xml version="1.0" encoding="UTF-8"?>
            <score-partwise version="4.0">
              <part-list><score-part id="P1"><part-name>Piano</part-name></score-part></part-list>
              <part id="P1"><measure number="1">
                <attributes><divisions>1</divisions><time><beats>4</beats><beat-type>4</beat-type></time></attributes>
                <note><pitch><step>C</step><octave>4</octave></pitch><duration>4</duration>
                  <tie type="start"/><tie type="stop"/><voice>1</voice><type>whole</type>
                  <notations><tied type="start"/><tied type="stop"/></notations>
                </note>
              </measure></part>
            </score-partwise>
        """.trimIndent()

        val normalized = MusicXmlCompatibilityNormalizer.normalize(source)
        val compact = normalized.replace(Regex("\\s+"), "")
        val event = MusicXmlScoreParser.parse(normalized).events.single()

        assertTrue(compact.indexOf("<tietype=\"stop\"") < compact.indexOf("<tietype=\"start\""))
        assertTrue(compact.indexOf("<tiedtype=\"stop\"") < compact.indexOf("<tiedtype=\"start\""))
        assertTrue(event.tieStart)
        assertTrue(event.tieStop)
    }

    @Test
    fun changesDurationUsingMusicXmlDivisionsAndTupletRatio() {
        assertEquals(8L, MusicalDuration("quarter", actualNotes = 3, normalNotes = 2).toDivisions(12))
        val original = MusicXmlScoreParser.parse(xml)
        val target = original.events.first { event ->
            !event.isChordTone && original.eventsInMeasure(event.measureIndex).count {
                it.partIndex == event.partIndex && it.voice == event.voice && it.staff == event.staff &&
                    it.onsetDivisions == event.onsetDivisions
            } == 1
        }
        val duration = MusicalDuration("quarter")

        val changed = MusicXmlRevisionCompiler.apply(
            xml,
            CorrectionOperation.ChangeDuration(target.id, duration),
        )
        val divisions = changed.score.parts[target.partIndex].measures[target.measureIndex - 1].divisions

        assertEquals(duration.toDivisions(divisions), changed.changedEvent.durationDivisions)
        assertEquals("quarter", changed.changedEvent.noteType)
        assertNull(changed.changedEvent.tupletActualNotes)
    }

    @Test
    fun malformedPitchesUseOneParseErrorBoundary() {
        listOf(
            "<step>C</step><alter>not-a-number</alter><octave>4</octave>",
            "<step>C</step><alter>3</alter><octave>4</octave>",
            "<step>C</step><octave>10</octave>",
            "<step>CC</step><octave>4</octave>",
        ).forEach { pitch ->
            val source = """
                <score-partwise>
                  <part-list><score-part id="P1"><part-name>Piano</part-name></score-part></part-list>
                  <part id="P1"><measure number="1">
                    <attributes><divisions>1</divisions><time><beats>1</beats><beat-type>4</beat-type></time></attributes>
                    <note><pitch>$pitch</pitch><duration>1</duration></note>
                  </measure></part>
                </score-partwise>
            """.trimIndent()

            val error = assertThrows(IllegalArgumentException::class.java) {
                MusicXmlScoreParser.parse(source)
            }
            assertTrue(error.message.orEmpty().startsWith("无法解析 MusicXML 音高："))
        }
    }

    @Test
    fun transposeRejectsOutOfRangePitchInsteadOfClamping() {
        assertThrows(IllegalArgumentException::class.java) {
            ScorePitch('G', 0, 9).transpose(1, preferSharps = true)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ScorePitch('C', 0, 0).transpose(-1, preferSharps = false)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ScorePitch('C', 0, 4).transpose(Int.MAX_VALUE, preferSharps = true)
        }
    }

    @Test
    fun durationChangeShiftsFollowingVoiceEventAndKeepsOtherVoiceOnset() {
        val source = """
            <score-partwise version="4.0">
              <part-list><score-part id="P1"><part-name>Piano</part-name></score-part></part-list>
              <part id="P1"><measure number="1">
                <attributes><divisions>1</divisions><time><beats>4</beats><beat-type>4</beat-type></time></attributes>
                <note><pitch><step>C</step><octave>4</octave></pitch><duration>1</duration><voice>1</voice><type>quarter</type></note>
                <note><pitch><step>D</step><octave>4</octave></pitch><duration>1</duration><voice>1</voice><type>quarter</type></note>
                <backup><duration>2</duration></backup>
                <note><pitch><step>E</step><octave>3</octave></pitch><duration>1</duration><voice>2</voice><type>quarter</type></note>
              </measure></part>
            </score-partwise>
        """.trimIndent()
        val original = MusicXmlScoreParser.parse(source)
        val firstVoice = original.events.filter { it.voice == "1" }
        val otherVoice = original.events.single { it.voice == "2" }

        val changed = MusicXmlRevisionCompiler.apply(
            source,
            CorrectionOperation.ChangeDuration(firstVoice.first().id, MusicalDuration("half")),
        )

        assertEquals(2L, changed.score.findEvent(firstVoice[1].id)?.onsetDivisions)
        assertEquals(otherVoice.onsetDivisions, changed.score.findEvent(otherVoice.id)?.onsetDivisions)
        assertTrue(changed.xml.replace(Regex("\\s+"), "").contains("<backup><duration>3</duration></backup>"))
        assertTrue(ScoreIrValidator.validate(changed.score).isEmpty())
    }

    @Test
    fun convertsStandaloneNoteToRestAndBackWithoutChangingEventIdentity() {
        val original = MusicXmlScoreParser.parse(xml)
        val target = original.events.first { event ->
            event.pitch != null && !event.isChordTone && original.eventsInMeasure(event.measureIndex).count {
                it.partIndex == event.partIndex && it.voice == event.voice && it.staff == event.staff &&
                    it.onsetDivisions == event.onsetDivisions
            } == 1
        }
        val pitch = requireNotNull(target.pitch)

        val rest = MusicXmlRevisionCompiler.apply(
            xml,
            CorrectionOperation.ChangeRest(target.id, makeRest = true),
        )
        assertTrue(rest.changedEvent.isRest)
        assertNull(rest.changedEvent.pitch)

        val note = MusicXmlRevisionCompiler.apply(
            rest.xml,
            CorrectionOperation.ChangeRest(target.id, makeRest = false, pitchWhenNote = pitch),
        )
        assertFalse(note.changedEvent.isRest)
        assertEquals(pitch, note.changedEvent.pitch)
    }

    @Test
    fun removesAndRestoresTieToTheNextMatchingOnset() {
        val original = MusicXmlScoreParser.parse(xml)
        val target = original.events.first { it.tieStart && it.pitch != null }

        val removed = MusicXmlRevisionCompiler.apply(
            xml,
            CorrectionOperation.SetTieToNext(target.id, enabled = false),
        )
        assertFalse(removed.changedEvent.tieStart)

        val restored = MusicXmlRevisionCompiler.apply(
            removed.xml,
            CorrectionOperation.SetTieToNext(target.id, enabled = true),
        )
        assertTrue(restored.changedEvent.tieStart)
    }
}
