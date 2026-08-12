package com.gpiano.app.scoreworkspace

import kotlin.math.abs

enum class PracticeTopic(val displayName: String) {
    Overview("练习顺序"),
    Tuplet("三连音"),
    Rhythm("节奏"),
    TieAndSlur("连线"),
    Coordination("左右手配合"),
    Fingering("落键与指法"),
    Pitch("音高"),
}

data class GuidanceEvidence(
    val measureIndexes: List<Int>,
    val eventIds: List<String>,
    val description: String,
)

data class PracticeInstruction(
    val order: Int,
    val text: String,
)

data class RecommendedPlayback(
    val speed: Double,
    val hand: PlaybackHand,
    val looping: Boolean = true,
)

data class PracticeGuidance(
    val id: String,
    val topic: PracticeTopic,
    val title: String,
    val explanation: String,
    val evidence: GuidanceEvidence,
    val instructions: List<PracticeInstruction>,
    val recommendedPlayback: RecommendedPlayback? = null,
)

data class PracticeAnalysis(
    val fromMeasure: Int,
    val toMeasure: Int,
    val overview: String,
    val eventCount: Int,
    val soundingEventCount: Int,
    val guidance: List<PracticeGuidance>,
    val engineId: String = "score-ir-rules-v1",
)

/**
 * Produces auditable practice guidance from the current ScoreIR projection.
 *
 * This is deliberately deterministic: every statement has score evidence and can be regenerated
 * without uploading the user's score. A generative model may later explain or reorder these facts,
 * but it must not invent facts outside this result.
 */
object ScorePracticeAnalyzer {
    fun analyze(score: ScoreIr, fromMeasure: Int, toMeasure: Int): PracticeAnalysis {
        require(fromMeasure in 1..score.measureCount) { "起始小节超出乐谱范围" }
        require(toMeasure in fromMeasure..score.measureCount) { "结束小节超出乐谱范围" }

        val measures = score.parts.flatMap { part ->
            part.measures.filter { it.index in fromMeasure..toMeasure }
        }
        val events = measures.flatMap(ScoreMeasureIr::events)
        val sounding = events.filter { !it.isRest && it.pitch != null }
        val rangeLabel = measureRangeLabel(fromMeasure, toMeasure)
        val cards = mutableListOf<PracticeGuidance>()

        val tuplets = events.filter { it.tupletActualNotes != null && it.tupletNormalNotes != null }
        if (tuplets.isNotEmpty()) {
            val ratios = tuplets.groupingBy { "${it.tupletActualNotes}:${it.tupletNormalNotes}" }
                .eachCount()
                .entries
                .sortedByDescending(Map.Entry<String, Int>::value)
            val mainRatio = ratios.first().key
            cards += PracticeGuidance(
                id = "tuplet:$fromMeasure:$toMeasure",
                topic = PracticeTopic.Tuplet,
                title = "$rangeLabel 有 ${tuplets.size} 个三连音相关音符",
                explanation = "主要比例为 $mainRatio。比例 3:2 表示把三个等分音均匀放进两个同值音符原本占用的时间，不是把第三个音拖长。",
                evidence = tuplets.toEvidence("MusicXML 的 time-modification 标记了 ${ratios.joinToString { "${it.key} × ${it.value}" }}。"),
                instructions = orderedInstructions(
                    "先离开琴键，用“1-2-3”均匀数拍 3 遍，每组完整占满原拍值。",
                    "以 50% 速度循环，只弹包含三连音的手，连续 3 遍保持三音间隔相等。",
                    "升到 75% 后合手 3 遍；一旦第三个音挤拍，就回到 50%。",
                ),
                recommendedPlayback = RecommendedPlayback(0.5, recommendedHand(tuplets)),
            )
        }

        val simultaneousHands = sounding
            .groupBy { event -> event.positionKey(score) }
            .values
            .filter { group ->
                group.any { it.hand == ScoreHand.Right } && group.any { it.hand == ScoreHand.Left }
            }
        if (simultaneousHands.isNotEmpty()) {
            val anchors = simultaneousHands.flatten().distinctBy(ScoreEventIr::id)
            cards += PracticeGuidance(
                id = "coordination:$fromMeasure:$toMeasure",
                topic = PracticeTopic.Coordination,
                title = "$rangeLabel 有 ${simultaneousHands.size} 个双手共同落点",
                explanation = "这些拍位是合手练习的对齐锚点。先让两只手各自稳定，再只检查共同落点，比从头到尾反复重弹更容易定位错位。",
                evidence = anchors.toEvidence("右手和左手在相同小节、相同拍位同时发音。"),
                instructions = orderedInstructions(
                    "右手和左手分别慢练 2 遍，确认各自节奏不依赖另一只手。",
                    "合手时只弹共同落点，停住检查两手是否同时落下。",
                    "以 75% 速度循环完整片段 3 遍，记录第一次错位所在的小节。",
                ),
                recommendedPlayback = RecommendedPlayback(0.75, PlaybackHand.Both),
            )
        }

        val ties = events.filter { it.tieStart || it.tieStop }
        val slurs = events.filter { it.slurStart || it.slurStop }
        if (ties.isNotEmpty() || slurs.isNotEmpty()) {
            val explanation = buildString {
                if (ties.isNotEmpty()) append("延音线连接相同音高：后一个音只延长时值，不重新按键。")
                if (ties.isNotEmpty() && slurs.isNotEmpty()) append(" ")
                if (slurs.isNotEmpty()) append("圆滑线表示乐句连贯，线内各音仍需要依次发音。")
            }
            cards += PracticeGuidance(
                id = "lines:$fromMeasure:$toMeasure",
                topic = PracticeTopic.TieAndSlur,
                title = "$rangeLabel 需要区分延音线与圆滑线",
                explanation = explanation,
                evidence = (ties + slurs).distinctBy(ScoreEventIr::id).toEvidence(
                    "识别到延音线相关事件 ${ties.size} 个、圆滑线相关事件 ${slurs.size} 个。",
                ),
                instructions = orderedInstructions(
                    "先圈出相同音高之间的延音线，口头说出“保持、不重按”。",
                    "单独慢弹线内音符，延音线处听持续，圆滑线处听音与音之间是否连贯。",
                    "以 50% 速度循环 3 遍，再打开谱面光标检查进入下一拍的时机。",
                ),
                recommendedPlayback = RecommendedPlayback(0.5, recommendedHand(ties + slurs)),
            )
        }

        val chordGroups = sounding.groupBy { event -> event.chordKey(score) }
            .values
            .filter { it.size > 1 }
        val widestChord = chordGroups.maxByOrNull { chordSpan(it) }
        val leaps = findLeaps(score, sounding)
        val largestLeap = leaps.maxByOrNull(LeapEvidence::semitones)
        if (widestChord != null && (widestChord.size >= 4 || chordSpan(widestChord) >= 12) || largestLeap != null && largestLeap.semitones >= 8) {
            val chordDescription = widestChord?.let {
                "最大和弦含 ${it.size} 个音、跨度 ${chordSpan(it)} 个半音"
            }
            val leapDescription = largestLeap?.let { "最大旋律跳进 ${it.semitones} 个半音" }
            val evidenceEvents = buildList {
                widestChord?.let(::addAll)
                largestLeap?.let { add(it.from); add(it.to) }
            }.distinctBy(ScoreEventIr::id)
            cards += PracticeGuidance(
                id = "fingering:$fromMeasure:$toMeasure",
                topic = PracticeTopic.Fingering,
                title = "$rangeLabel 有需要预先找落点的跨度",
                explanation = listOfNotNull(chordDescription, leapDescription).joinToString("；") +
                    "。这里不把某一套指法当成唯一答案，应先验证手位转换、连贯性和身体是否放松。",
                evidence = evidenceEvents.toEvidence("根据同时发音的音高跨度和同手相邻落点计算。"),
                instructions = orderedInstructions(
                    "不出声地在键盘上找到起点和终点，手腕保持放松，重复移动 5 次。",
                    "把跨度前后的两个落点单独连起来慢弹 3 次，再补回中间音。",
                    "若出现紧张、扭腕或无法同时落键，先分解和弦，不强行拉伸，并在派生版本中审阅化简方案。",
                ),
                recommendedPlayback = RecommendedPlayback(0.5, recommendedHand(evidenceEvents)),
            )
        }

        val rhythmValues = events.filter { it.durationDivisions > 0 }
            .groupBy { event -> event.durationKey(score) }
        val dotted = events.filter { it.dots > 0 }
        val shortValues = events.filter { event ->
            val divisions = score.measureFor(event)?.divisions ?: return@filter false
            event.durationDivisions * 2 < divisions
        }
        if (rhythmValues.size >= 3 || dotted.isNotEmpty() || shortValues.isNotEmpty()) {
            val evidenceEvents = (dotted + shortValues + events.take(4)).distinctBy(ScoreEventIr::id)
            cards += PracticeGuidance(
                id = "rhythm:$fromMeasure:$toMeasure",
                topic = PracticeTopic.Rhythm,
                title = "$rangeLabel 有 ${rhythmValues.size} 类时值关系",
                explanation = "这一段同时出现不同长度的发音或休止。先固定拍点和细分，再加入音高，可以避免因为找键而改变节奏。",
                evidence = evidenceEvents.toEvidence(
                    "按 MusicXML divisions 归一化后识别到 ${rhythmValues.size} 类时值" +
                        if (dotted.isNotEmpty()) "，其中 ${dotted.size} 个事件带附点。" else "。",
                ),
                instructions = orderedInstructions(
                    "用手拍或单一琴键读完整节奏 3 遍，休止处也继续数拍。",
                    "以 50% 速度跟随试听，只检查每个发音进入和结束的位置。",
                    "恢复原音高，以 75% 速度连续 3 遍；错误后从该拍重新开始，不必整段重来。",
                ),
                recommendedPlayback = RecommendedPlayback(0.5, PlaybackHand.Both),
            )
        }

        val altered = sounding.filter { it.pitch?.alter != 0 }
        if (score.fifths != null || altered.isNotEmpty()) {
            val signature = keySignatureDescription(score.fifths)
            cards += PracticeGuidance(
                id = "pitch:$fromMeasure:$toMeasure",
                topic = PracticeTopic.Pitch,
                title = "$rangeLabel 先确认调号和变化音",
                explanation = "$signature。当前范围有 ${altered.size} 个带升降信息的发音事件；校正后的结构化音高可作为按键核对依据。",
                evidence = (altered.ifEmpty { sounding.take(4) }).toEvidence("依据 MusicXML 的 fifths 与 pitch/alter 字段。"),
                instructions = orderedInstructions(
                    "开弹前说出调号中的固定升降音，再在键盘上依次找到它们。",
                    "先弹每拍的第一个音，核对落键位置后再补齐其余音符。",
                    "发现谱面与原图不一致时先回到校正面板，不在错误草稿上反复练习。",
                ),
                recommendedPlayback = RecommendedPlayback(0.75, recommendedHand(sounding)),
            )
        }

        val ordered = cards.sortedBy { topicOrder(it.topic) }.take(6)
        val overviewCard = PracticeGuidance(
            id = "overview:$fromMeasure:$toMeasure",
            topic = PracticeTopic.Overview,
            title = "$rangeLabel 的建议练习顺序",
            explanation = if (ordered.isEmpty()) {
                "当前结构没有检测到需要单独提示的复杂记号，仍建议用试听核对节奏与音高后再逐步提速。"
            } else {
                "优先处理 ${ordered.take(3).joinToString("、") { it.topic.displayName }}，再进行整段连贯练习。"
            },
            evidence = events.toEvidence("当前选择包含 ${events.size} 个事件，其中 ${sounding.size} 个发音事件。"),
            instructions = orderedInstructions(
                "先看原谱与结构化谱是否一致；有识别错误时先校正。",
                "按下方优先项逐个练习，每项连续正确 3 遍再进入下一项。",
                "最后以 75% 速度循环整段，稳定后再回到原速。",
            ),
            recommendedPlayback = RecommendedPlayback(0.75, PlaybackHand.Both),
        )

        return PracticeAnalysis(
            fromMeasure = fromMeasure,
            toMeasure = toMeasure,
            overview = "$rangeLabel · ${events.size} 个结构事件 · ${sounding.size} 个发音事件",
            eventCount = events.size,
            soundingEventCount = sounding.size,
            guidance = listOf(overviewCard) + ordered,
        )
    }

    private fun orderedInstructions(vararg texts: String): List<PracticeInstruction> =
        texts.mapIndexed { index, text -> PracticeInstruction(index + 1, text) }

    private fun List<ScoreEventIr>.toEvidence(description: String): GuidanceEvidence = GuidanceEvidence(
        measureIndexes = map(ScoreEventIr::measureIndex).distinct().sorted(),
        eventIds = map(ScoreEventIr::id).distinct().take(12),
        description = description,
    )

    private fun recommendedHand(events: List<ScoreEventIr>): PlaybackHand {
        val hands = events.map(ScoreEventIr::hand).filter { it != ScoreHand.Unknown }.toSet()
        return when (hands) {
            setOf(ScoreHand.Right) -> PlaybackHand.Right
            setOf(ScoreHand.Left) -> PlaybackHand.Left
            else -> PlaybackHand.Both
        }
    }

    private fun findLeaps(score: ScoreIr, events: List<ScoreEventIr>): List<LeapEvidence> = events
        .filterNot(ScoreEventIr::isChordTone)
        .groupBy { listOf(it.partIndex, it.voice, it.staff ?: -1, it.hand.ordinal) }
        .values
        .flatMap { voiceEvents ->
            voiceEvents.sortedWith(
                compareBy<ScoreEventIr> { it.measureIndex }
                    .thenBy { it.positionInQuarterNotes(score) },
            )
                .zipWithNext()
                .mapNotNull { (from, to) ->
                    val first = from.pitch ?: return@mapNotNull null
                    val second = to.pitch ?: return@mapNotNull null
                    LeapEvidence(from, to, abs(second.midi - first.midi))
                }
        }

    private fun ScoreEventIr.positionKey(score: ScoreIr): List<Long> {
        val measure = score.measureFor(this)
        val divisions = measure?.divisions?.toLong()?.coerceAtLeast(1) ?: 1L
        val divisor = gcd(abs(onsetDivisions), divisions).coerceAtLeast(1)
        return listOf(measureIndex.toLong(), onsetDivisions / divisor, divisions / divisor)
    }

    private fun ScoreEventIr.chordKey(score: ScoreIr): List<Any> =
        positionKey(score) + listOf(partIndex, voice, staff ?: -1, hand.ordinal)

    private fun ScoreEventIr.durationKey(score: ScoreIr): Pair<Long, Long> {
        val divisions = score.measureFor(this)?.divisions?.toLong()?.coerceAtLeast(1) ?: 1L
        val divisor = gcd(abs(durationDivisions), divisions).coerceAtLeast(1)
        return durationDivisions / divisor to divisions / divisor
    }

    private fun ScoreEventIr.positionInQuarterNotes(score: ScoreIr): Double {
        val divisions = score.measureFor(this)?.divisions?.toDouble()?.coerceAtLeast(1.0) ?: 1.0
        return measureIndex * 10_000.0 + onsetDivisions / divisions
    }

    private fun ScoreIr.measureFor(event: ScoreEventIr): ScoreMeasureIr? =
        parts.getOrNull(event.partIndex)?.measures?.getOrNull(event.measureIndex - 1)

    private fun chordSpan(events: List<ScoreEventIr>): Int {
        val pitches = events.mapNotNull { it.pitch?.midi }
        return if (pitches.isEmpty()) 0 else pitches.max() - pitches.min()
    }

    private fun gcd(first: Long, second: Long): Long {
        var a = first
        var b = second
        while (b != 0L) {
            val remainder = a % b
            a = b
            b = remainder
        }
        return abs(a)
    }

    private fun keySignatureDescription(fifths: Int?): String = when {
        fifths == null -> "调号未识别"
        fifths == 0 -> "调号没有固定升降号"
        fifths > 0 -> "调号包含 $fifths 个升号"
        else -> "调号包含 ${abs(fifths)} 个降号"
    }

    private fun topicOrder(topic: PracticeTopic): Int = when (topic) {
        PracticeTopic.Tuplet -> 0
        PracticeTopic.Coordination -> 1
        PracticeTopic.TieAndSlur -> 2
        PracticeTopic.Fingering -> 3
        PracticeTopic.Rhythm -> 4
        PracticeTopic.Pitch -> 5
        PracticeTopic.Overview -> -1
    }

    private fun measureRangeLabel(from: Int, to: Int): String =
        if (from == to) "第 $from 小节" else "第 $from–$to 小节"

    private data class LeapEvidence(
        val from: ScoreEventIr,
        val to: ScoreEventIr,
        val semitones: Int,
    )
}
