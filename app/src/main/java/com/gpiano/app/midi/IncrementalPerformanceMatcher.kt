package com.gpiano.app.midi

import com.gpiano.app.scoreworkspace.PlaybackEvent
import com.gpiano.app.scoreworkspace.PlaybackPlan

enum class LiveFeedbackKind {
    Target,
    Correct,
    Incorrect,
    Missing,
}

data class LiveNoteFeedback(
    val eventId: String,
    val measureIndex: Int,
    val midiPitch: Int,
    val kind: LiveFeedbackKind,
)

data class LivePerformanceFeedback(
    val elapsedMillis: Long = 0L,
    val currentMeasure: Int? = null,
    val completedCount: Int = 0,
    val totalCount: Int = 0,
    val progress: Float = 0f,
    val notes: List<LiveNoteFeedback> = emptyList(),
)

/** Re-evaluates the captured prefix with [PerformanceMatcher] so live and final rules stay identical. */
object IncrementalPerformanceMatcher {
    fun evaluate(
        plan: PlaybackPlan,
        performed: List<PerformedMidiNote>,
        elapsedMillis: Long,
    ): LivePerformanceFeedback {
        val targets = PerformanceMatcher.expectedAttacks(plan)
        if (targets.isEmpty()) return LivePerformanceFeedback()
        val elapsed = elapsedMillis.coerceAtLeast(0L)
        val report = PerformanceMatcher.match(plan, performed)
        val targetById = targets.associateBy(PlaybackEvent::eventId)
        val actualBySequence = performed.associateBy(PerformedMidiNote::sequence)
        val matchedByTarget = report.matches
            .asSequence()
            .filter { it.targetEventId != null }
            .associateBy { requireNotNull(it.targetEventId) }
        val dueTarget = targets.firstOrNull { target ->
            targetMillis(plan, target) + report.associationToleranceMillis >= elapsed
        }
        val dueTick = dueTarget?.startTick
        val targetFeedback = targets.mapNotNull { target ->
            val targetTime = targetMillis(plan, target)
            val match = matchedByTarget[target.eventId]
            val actualHasOccurred = match?.actualSequence?.let(actualBySequence::get)?.let { actual ->
                actual.onsetNanos / 1_000_000L <= elapsed
            } == true
            val kind = when {
                actualHasOccurred && match?.kind == MatchKind.Correct -> LiveFeedbackKind.Correct
                actualHasOccurred && match?.kind in setOf(
                    MatchKind.RhythmEarly,
                    MatchKind.RhythmLate,
                    MatchKind.WrongPitch,
                ) -> LiveFeedbackKind.Incorrect
                elapsed > targetTime + report.associationToleranceMillis -> LiveFeedbackKind.Missing
                target.startTick == dueTick -> LiveFeedbackKind.Target
                else -> null
            }
            kind?.let { LiveNoteFeedback(target.eventId, target.measureIndex, target.midiPitch, it) }
        }
        val currentMeasure = dueTarget?.measureIndex ?: targets.last().measureIndex
        val extraFeedback = report.matches.mapNotNull { match ->
            if (match.kind != MatchKind.Extra) return@mapNotNull null
            val sequence = match.actualSequence ?: return@mapNotNull null
            val actual = actualBySequence[sequence] ?: return@mapNotNull null
            if (actual.onsetNanos / 1_000_000L > elapsed) return@mapNotNull null
            LiveNoteFeedback(
                eventId = "actual:$sequence",
                measureIndex = currentMeasure,
                midiPitch = actual.midiPitch,
                kind = LiveFeedbackKind.Incorrect,
            )
        }
        val feedback = targetFeedback + extraFeedback
        val completed = targetFeedback.count { it.kind != LiveFeedbackKind.Target }
        return LivePerformanceFeedback(
            elapsedMillis = elapsed,
            currentMeasure = currentMeasure,
            completedCount = completed,
            totalCount = targets.size,
            progress = (elapsed.toFloat() / plan.durationMillis.coerceAtLeast(1L)).coerceIn(0f, 1f),
            notes = feedback,
        )
    }

    private fun targetMillis(plan: PlaybackPlan, target: PlaybackEvent): Long =
        PerformanceMatcher.ticksToMillis(plan, target.startTick - plan.rangeStartTick)
}
