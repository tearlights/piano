package com.gpiano.app.midi

import com.gpiano.app.scoreworkspace.PlaybackEvent
import com.gpiano.app.scoreworkspace.PlaybackPlan
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.roundToLong

data class PerformedMidiNote(
    val sequence: Int,
    val channel: Int,
    val midiPitch: Int,
    val velocity: Int,
    val onsetNanos: Long,
    val durationNanos: Long?,
) {
    init {
        require(sequence >= 0) { "MIDI 事件序号无效" }
        require(channel in 0..15) { "MIDI 通道无效" }
        require(midiPitch in 0..127) { "MIDI 音高无效" }
        require(velocity in 0..127) { "MIDI 力度无效" }
        require(onsetNanos >= 0) { "MIDI 起始时间无效" }
        require(durationNanos == null || durationNanos >= 0) { "MIDI 持续时间无效" }
    }
}

enum class MatchKind {
    Correct,
    RhythmEarly,
    RhythmLate,
    WrongPitch,
    Missing,
    Extra,
}

data class PerformanceMatch(
    val kind: MatchKind,
    val measureIndex: Int?,
    val targetEventId: String?,
    val expectedPitch: Int?,
    val actualSequence: Int?,
    val actualPitch: Int?,
    val timingErrorMillis: Long?,
)

data class PerformanceReport(
    val expectedCount: Int,
    val playedCount: Int,
    val pitchMatchedCount: Int,
    val rhythmMatchedCount: Int,
    val missedCount: Int,
    val extraCount: Int,
    val wrongPitchCount: Int,
    val continuityPercent: Int,
    val pitchAccuracyPercent: Int,
    val rhythmAccuracyPercent: Int,
    val associationToleranceMillis: Long,
    val rhythmToleranceMillis: Long,
    val matches: List<PerformanceMatch>,
) {
    val completedTarget: Boolean get() = expectedCount > 0 && missedCount == 0
}

object PerformanceMatcher {
    fun match(plan: PlaybackPlan, performed: List<PerformedMidiNote>): PerformanceReport {
        val targets = expectedAttacks(plan)
        require(targets.isNotEmpty()) { "目标片段没有需要按下的音符" }
        val quarterMillis = 60_000.0 / (plan.tempoBpm * plan.selection.speed)
        val associationTolerance = (quarterMillis * 0.75).roundToLong().coerceIn(240L, 900L)
        val rhythmTolerance = (quarterMillis * 0.20).roundToLong().coerceIn(90L, 300L)
        val expectedOrigin = targets.minOf(PlaybackEvent::startTick)
        val actualOrigin = performed.minOfOrNull(PerformedMidiNote::onsetNanos) ?: 0L
        val actual = performed.sortedWith(compareBy(PerformedMidiNote::onsetNanos, PerformedMidiNote::sequence))
            .map { note -> TimedActual(note, (note.onsetNanos - actualOrigin) / 1_000_000L) }
        val unused = actual.indices.toMutableSet()
        val result = mutableListOf<PerformanceMatch>()

        targets.forEach { target ->
            val targetMillis = ticksToMillis(plan, target.startTick - expectedOrigin)
            val exact = unused.asSequence()
                .map { index -> index to actual[index] }
                .filter { (_, candidate) -> candidate.note.midiPitch == target.midiPitch }
                .minByOrNull { (_, candidate) -> abs(candidate.relativeMillis - targetMillis) }
                ?.takeIf { (_, candidate) -> abs(candidate.relativeMillis - targetMillis) <= associationTolerance }
            val nearest = exact ?: unused.asSequence()
                .map { index -> index to actual[index] }
                .minByOrNull { (_, candidate) -> abs(candidate.relativeMillis - targetMillis) }
                ?.takeIf { (_, candidate) -> abs(candidate.relativeMillis - targetMillis) <= associationTolerance }

            if (nearest == null) {
                result += PerformanceMatch(
                    MatchKind.Missing,
                    target.measureIndex,
                    target.eventId,
                    target.midiPitch,
                    null,
                    null,
                    null,
                )
            } else {
                unused -= nearest.first
                val note = nearest.second.note
                val error = nearest.second.relativeMillis - targetMillis
                val kind = when {
                    note.midiPitch != target.midiPitch -> MatchKind.WrongPitch
                    abs(error) <= rhythmTolerance -> MatchKind.Correct
                    error < 0 -> MatchKind.RhythmEarly
                    else -> MatchKind.RhythmLate
                }
                result += PerformanceMatch(
                    kind,
                    target.measureIndex,
                    target.eventId,
                    target.midiPitch,
                    note.sequence,
                    note.midiPitch,
                    error,
                )
            }
        }
        unused.sorted().forEach { index ->
            val note = actual[index].note
            result += PerformanceMatch(
                MatchKind.Extra,
                null,
                null,
                null,
                note.sequence,
                note.midiPitch,
                null,
            )
        }

        val pitchMatches = result.count { it.kind in setOf(MatchKind.Correct, MatchKind.RhythmEarly, MatchKind.RhythmLate) }
        val rhythmMatches = result.count { it.kind == MatchKind.Correct }
        val missed = result.count { it.kind == MatchKind.Missing }
        val extra = result.count { it.kind == MatchKind.Extra }
        val wrong = result.count { it.kind == MatchKind.WrongPitch }
        val orderedTargets = result.filter { it.targetEventId != null }
        var currentRun = 0
        var longestRun = 0
        orderedTargets.forEach { match ->
            if (match.kind == MatchKind.Correct) {
                currentRun += 1
                longestRun = max(longestRun, currentRun)
            } else {
                currentRun = 0
            }
        }
        return PerformanceReport(
            expectedCount = targets.size,
            playedCount = performed.size,
            pitchMatchedCount = pitchMatches,
            rhythmMatchedCount = rhythmMatches,
            missedCount = missed,
            extraCount = extra,
            wrongPitchCount = wrong,
            continuityPercent = percent(longestRun, targets.size),
            pitchAccuracyPercent = percent(pitchMatches, targets.size),
            rhythmAccuracyPercent = percent(rhythmMatches, targets.size),
            associationToleranceMillis = associationTolerance,
            rhythmToleranceMillis = rhythmTolerance,
            matches = result,
        )
    }

    fun expectedAttacks(plan: PlaybackPlan): List<PlaybackEvent> {
        val selected = plan.events.sortedWith(compareBy(PlaybackEvent::startTick, PlaybackEvent::midiPitch, PlaybackEvent::eventId))
        return selected.filter { event ->
            if (!event.tieStop) return@filter true
            selected.none { previous ->
                previous !== event && previous.midiPitch == event.midiPitch && previous.hand == event.hand &&
                    previous.tieStart && previous.startTick + previous.durationTick == event.startTick
            }
        }
    }

    private fun ticksToMillis(plan: PlaybackPlan, ticks: Long): Long =
        (ticks * 60_000.0 / (plan.tempoBpm * plan.ticksPerQuarter) / plan.selection.speed).roundToLong()

    private fun percent(value: Int, total: Int): Int = if (total == 0) 0 else (value * 100.0 / total).roundToInt()

    private data class TimedActual(val note: PerformedMidiNote, val relativeMillis: Long)
}
