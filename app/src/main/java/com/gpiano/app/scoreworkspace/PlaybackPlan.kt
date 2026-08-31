package com.gpiano.app.scoreworkspace

import kotlin.math.roundToLong

enum class PlaybackHand {
    Both,
    Right,
    Left,
}

data class PlaybackSelection(
    val startMeasure: Int,
    val endMeasure: Int,
    val hand: PlaybackHand = PlaybackHand.Both,
    val speed: Double = 1.0,
    val looping: Boolean = false,
) {
    init {
        require(startMeasure > 0) { "播放起始小节必须大于 0" }
        require(endMeasure >= startMeasure) { "播放结束小节不能早于起始小节" }
        require(speed in 0.25..2.0) { "播放速度超出支持范围" }
    }
}

data class PlaybackEvent(
    val eventId: String,
    val measureIndex: Int,
    val hand: ScoreHand,
    val startTick: Long,
    val durationTick: Long,
    val midiPitch: Int,
    val tieStart: Boolean,
    val tieStop: Boolean,
)

data class PlaybackMeasure(
    val measureIndex: Int,
    val startTick: Long,
    val endTick: Long,
)

data class PlaybackPlan(
    val selection: PlaybackSelection,
    val ticksPerQuarter: Int,
    val tempoBpm: Double,
    val rangeStartTick: Long,
    val rangeEndTick: Long,
    val measures: List<PlaybackMeasure>,
    val events: List<PlaybackEvent>,
) {
    val durationTick: Long get() = rangeEndTick - rangeStartTick
    val durationMillis: Long
        get() = (durationTick * 60_000.0 / (tempoBpm * ticksPerQuarter) / selection.speed).roundToLong()
}

object PlaybackPlanCompiler {
    const val TICKS_PER_QUARTER = 960

    fun compile(score: ScoreIr, selection: PlaybackSelection): PlaybackPlan {
        require(selection.endMeasure <= score.measureCount) {
            "播放结束小节超出乐谱范围"
        }
        val allMeasures = mutableListOf<PlaybackMeasure>()
        val allEvents = mutableListOf<PlaybackEvent>()
        var measureStartTick = 0L

        for (measureIndex in 1..score.measureCount) {
            val partMeasures = score.parts.mapNotNull { it.measures.getOrNull(measureIndex - 1) }
            val eventEnds = partMeasures.flatMap { measure ->
                measure.events.map { event ->
                    toTicks(event.onsetDivisions + event.durationDivisions, measure.divisions)
                }
            }
            val actualDuration = eventEnds.maxOrNull() ?: 0L
            val reference = partMeasures.firstOrNull()
            val nominalDuration = nominalMeasureTicks(reference)
            val measureDuration = actualDuration.takeIf { it > 0 } ?: nominalDuration
            require(measureDuration > 0) { "第 $measureIndex 小节没有可确定的时长" }
            val measureEndTick = measureStartTick + measureDuration
            allMeasures += PlaybackMeasure(measureIndex, measureStartTick, measureEndTick)

            partMeasures.forEach { measure ->
                measure.events.asSequence()
                    .filter { !it.isRest && it.pitch != null && handMatches(it.hand, selection.hand) }
                    .forEach { event ->
                        val duration = toTicks(event.durationDivisions, measure.divisions)
                        if (duration > 0) {
                            allEvents += PlaybackEvent(
                                eventId = event.id,
                                measureIndex = measureIndex,
                                hand = event.hand,
                                startTick = measureStartTick + toTicks(event.onsetDivisions, measure.divisions),
                                durationTick = duration,
                                midiPitch = requireNotNull(event.pitch).midi,
                                tieStart = event.tieStart,
                                tieStop = event.tieStop,
                            )
                        }
                    }
            }
            measureStartTick = measureEndTick
        }

        val selectedMeasures = allMeasures.filter {
            it.measureIndex in selection.startMeasure..selection.endMeasure
        }
        val startTick = selectedMeasures.first().startTick
        val endTick = selectedMeasures.last().endTick
        return PlaybackPlan(
            selection = selection,
            ticksPerQuarter = TICKS_PER_QUARTER,
            tempoBpm = score.tempoBpm ?: 120.0,
            rangeStartTick = startTick,
            rangeEndTick = endTick,
            measures = selectedMeasures,
            events = allEvents
                .filter { it.measureIndex in selection.startMeasure..selection.endMeasure }
                .sortedWith(compareBy(PlaybackEvent::startTick, PlaybackEvent::midiPitch, PlaybackEvent::eventId)),
        )
    }

    private fun nominalMeasureTicks(measure: ScoreMeasureIr?): Long {
        val beats = measure?.beats ?: 4
        val beatType = measure?.beatType ?: 4
        require(beats > 0 && beatType > 0) { "MusicXML 拍号必须大于 0" }
        return Math.multiplyExact(Math.multiplyExact(beats.toLong(), TICKS_PER_QUARTER.toLong()), 4L)
            .div(beatType.toLong())
            .coerceAtLeast(1L)
    }

    private fun toTicks(value: Long, divisions: Int): Long {
        require(divisions > 0) { "MusicXML divisions 必须大于 0" }
        return (value.toDouble() * TICKS_PER_QUARTER / divisions).roundToLong()
    }

    private fun handMatches(hand: ScoreHand, filter: PlaybackHand): Boolean = when (filter) {
        PlaybackHand.Both -> true
        PlaybackHand.Right -> hand == ScoreHand.Right
        PlaybackHand.Left -> hand == ScoreHand.Left
    }
}
