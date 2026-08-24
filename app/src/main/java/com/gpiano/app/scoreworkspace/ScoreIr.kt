package com.gpiano.app.scoreworkspace

enum class ScoreHand {
    Right,
    Left,
    Unknown,
}

data class ScorePitch(
    val step: Char,
    val alter: Int,
    val octave: Int,
) {
    init {
        require(step in VALID_STEPS) { "无效音名：$step" }
        require(alter in -2..2) { "升降记号超出当前支持范围：$alter" }
        require(octave in 0..9) { "八度超出当前支持范围：$octave" }
    }

    val midi: Int
        get() = (octave + 1) * 12 + STEP_SEMITONES.getValue(step) + alter

    val displayName: String
        get() = buildString {
            append(step)
            append(
                when (alter) {
                    -2 -> "♭♭"
                    -1 -> "♭"
                    1 -> "♯"
                    2 -> "♯♯"
                    else -> ""
                },
            )
            append(octave)
        }

    fun transpose(semitones: Int, preferSharps: Boolean): ScorePitch {
        val targetMidi = (midi + semitones).coerceIn(0, 127)
        return fromMidi(targetMidi, preferSharps)
    }

    companion object {
        private val VALID_STEPS = setOf('A', 'B', 'C', 'D', 'E', 'F', 'G')
        private val STEP_SEMITONES = mapOf(
            'C' to 0,
            'D' to 2,
            'E' to 4,
            'F' to 5,
            'G' to 7,
            'A' to 9,
            'B' to 11,
        )
        private val SHARP_SPELLINGS = listOf(
            'C' to 0,
            'C' to 1,
            'D' to 0,
            'D' to 1,
            'E' to 0,
            'F' to 0,
            'F' to 1,
            'G' to 0,
            'G' to 1,
            'A' to 0,
            'A' to 1,
            'B' to 0,
        )
        private val FLAT_SPELLINGS = listOf(
            'C' to 0,
            'D' to -1,
            'D' to 0,
            'E' to -1,
            'E' to 0,
            'F' to 0,
            'G' to -1,
            'G' to 0,
            'A' to -1,
            'A' to 0,
            'B' to -1,
            'B' to 0,
        )

        fun fromMidi(midi: Int, preferSharps: Boolean = true): ScorePitch {
            require(midi in 0..127) { "MIDI 音高超出范围：$midi" }
            val spelling = (if (preferSharps) SHARP_SPELLINGS else FLAT_SPELLINGS)[midi % 12]
            return ScorePitch(
                step = spelling.first,
                alter = spelling.second,
                octave = midi / 12 - 1,
            )
        }
    }
}

data class ScoreEventIr(
    val id: String,
    val partIndex: Int,
    val partId: String,
    val partName: String?,
    val measureIndex: Int,
    val sourceMeasureNumber: String?,
    val noteIndex: Int,
    val voice: String,
    val staff: Int?,
    val hand: ScoreHand,
    val onsetDivisions: Long,
    val durationDivisions: Long,
    val isChordTone: Boolean,
    val isRest: Boolean,
    val pitch: ScorePitch?,
    val noteType: String?,
    val dots: Int,
    val tupletActualNotes: Int?,
    val tupletNormalNotes: Int?,
    val tieStart: Boolean,
    val tieStop: Boolean,
    val slurStart: Boolean,
    val slurStop: Boolean,
)

data class ScoreMeasureIr(
    val index: Int,
    val sourceNumber: String?,
    val divisions: Int,
    val beats: Int?,
    val beatType: Int?,
    val events: List<ScoreEventIr>,
)

data class ScorePartIr(
    val index: Int,
    val id: String,
    val name: String?,
    val measures: List<ScoreMeasureIr>,
)

data class ScoreIr(
    val title: String,
    val tempoBpm: Double?,
    val fifths: Int?,
    val beats: Int?,
    val beatType: Int?,
    val parts: List<ScorePartIr>,
) {
    val measureCount: Int = parts.maxOfOrNull { it.measures.size } ?: 0

    val events: List<ScoreEventIr>
        get() = parts.flatMap { part -> part.measures.flatMap(ScoreMeasureIr::events) }

    fun findEvent(id: String): ScoreEventIr? = events.firstOrNull { it.id == id }

    fun eventsInMeasure(measureIndex: Int): List<ScoreEventIr> = parts.flatMap { part ->
        part.measures.getOrNull(measureIndex - 1)?.events.orEmpty()
    }
}

object ScoreIrValidator {
    fun validate(score: ScoreIr): List<String> = buildList {
        if (score.parts.isEmpty()) add("乐谱没有声部")
        val ids = hashSetOf<String>()
        score.parts.forEachIndexed { partOffset, part ->
            if (part.index != partOffset) add("声部 ${part.id} 的索引不连续")
            part.measures.forEachIndexed { measureOffset, measure ->
                if (measure.index != measureOffset + 1) add("声部 ${part.id} 的小节索引不连续")
                if (measure.divisions <= 0) add("第 ${measure.index} 小节的 divisions 必须大于 0")
                if (measure.beats != null && measure.beats <= 0) add("第 ${measure.index} 小节的拍数必须大于 0")
                if (measure.beatType != null && measure.beatType <= 0) add("第 ${measure.index} 小节的拍号分母必须大于 0")
                measure.events.forEach { event ->
                    if (!ids.add(event.id)) add("元素 ID 重复：${event.id}")
                    if (event.partIndex != part.index || event.measureIndex != measure.index) {
                        add("${event.id} 的声部或小节索引不一致")
                    }
                    if (event.onsetDivisions < 0) add("${event.id} 的起始位置小于 0")
                    if (event.durationDivisions <= 0) add("${event.id} 的时长必须大于 0")
                    if (runCatching { Math.addExact(event.onsetDivisions, event.durationDivisions) }.isFailure) {
                        add("${event.id} 的结束位置溢出")
                    }
                    if (!event.isRest && event.pitch == null) add("${event.id} 缺少音高")
                    event.pitch?.let { pitch ->
                        if (pitch.midi !in 0..127) add("${event.id} 的 MIDI 音高超出范围")
                    }
                }
                measure.events.groupBy { it.voice to it.staff }.forEach { (_, voiceEvents) ->
                    val onsetGroups = voiceEvents.groupBy(ScoreEventIr::onsetDivisions).toSortedMap()
                    var previousEnd: Long? = null
                    onsetGroups.forEach { (onset, simultaneous) ->
                        previousEnd?.let { end ->
                            if (onset < end) add("${simultaneous.first().id} 与同声部前一事件重叠")
                        }
                        val groupEnd = simultaneous.mapNotNull { event ->
                            runCatching { Math.addExact(event.onsetDivisions, event.durationDivisions) }.getOrNull()
                        }.maxOrNull()
                        if (groupEnd != null) previousEnd = maxOf(previousEnd ?: groupEnd, groupEnd)
                    }
                }
            }
        }
    }

    fun requireValid(score: ScoreIr) {
        val errors = validate(score)
        require(errors.isEmpty()) { errors.joinToString(separator = "；") }
    }
}
