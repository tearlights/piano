package com.gpiano.app.scoreworkspace

import java.io.StringWriter
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult
import org.json.JSONArray
import org.json.JSONObject
import org.w3c.dom.Document
import org.w3c.dom.Element
import kotlin.math.abs

enum class PracticeVersionPreset(
    val code: String,
    val displayName: String,
    val purpose: String,
) {
    RightHandOnly("right-hand", "右手分手练习版", "保留右手结构，让左手暂时静音，集中练习右手的节奏与落键。"),
    LeftHandOnly("left-hand", "左手分手练习版", "保留左手结构，让右手暂时静音，集中练习左手的节奏与落键。"),
    ReducedReach(
        "reduced-reach",
        "降低跨距练习版",
        "减少过密或跨度过大的和弦，并用八度等价位置压缩过大跳进；会改变织体或音区，必须逐项审阅。",
    ),
    ;

    companion object {
        fun fromCode(code: String): PracticeVersionPreset = entries.firstOrNull { it.code == code }
            ?: error("不支持的练习版本类型：$code")
    }
}

data class PracticeEditPlan(
    val preset: PracticeVersionPreset,
    val fromMeasure: Int,
    val toMeasure: Int,
    val maxChordNotes: Int = 3,
    val maxChordSpanSemitones: Int = 12,
    val maxLeapSemitones: Int = 12,
) {
    init {
        require(fromMeasure >= 1 && toMeasure >= fromMeasure) { "练习版本小节范围无效" }
        require(maxChordNotes in 1..6) { "和弦保留音数无效" }
        require(maxChordSpanSemitones in 5..24) { "和弦跨度限制无效" }
        require(maxLeapSemitones in 5..24) { "跳进限制无效" }
    }

    fun toJson(): String = JSONObject()
        .put("version", 1)
        .put("preset", preset.code)
        .put("fromMeasure", fromMeasure)
        .put("toMeasure", toMeasure)
        .put("maxChordNotes", maxChordNotes)
        .put("maxChordSpanSemitones", maxChordSpanSemitones)
        .put("maxLeapSemitones", maxLeapSemitones)
        .toString()

    companion object {
        fun fromJson(json: String): PracticeEditPlan = JSONObject(json).let { value ->
            require(value.getInt("version") == 1) { "不支持的 EditPlan 版本" }
            PracticeEditPlan(
                preset = PracticeVersionPreset.fromCode(value.getString("preset")),
                fromMeasure = value.getInt("fromMeasure"),
                toMeasure = value.getInt("toMeasure"),
                maxChordNotes = value.optInt("maxChordNotes", 3),
                maxChordSpanSemitones = value.optInt("maxChordSpanSemitones", 12),
                maxLeapSemitones = value.optInt("maxLeapSemitones", 12),
            )
        }
    }
}

enum class PracticeDifferenceKind(val code: String) {
    Muted("muted"),
    RemovedChordTone("removed-chord-tone"),
    OctaveShift("octave-shift"),
    ;

    companion object {
        fun fromCode(code: String): PracticeDifferenceKind = entries.firstOrNull { it.code == code }
            ?: error("不支持的差异类型：$code")
    }
}

data class PracticeDifference(
    val eventId: String,
    val measureIndex: Int,
    val kind: PracticeDifferenceKind,
    val before: String,
    val after: String,
    val explanation: String,
)

data class PracticeVersionCompilation(
    val xml: String,
    val score: ScoreIr,
    val plan: PracticeEditPlan,
    val differences: List<PracticeDifference>,
)

object PracticeVersionCompiler {
    fun compile(xml: String, plan: PracticeEditPlan): PracticeVersionCompilation {
        val score = MusicXmlScoreParser.parse(xml)
        require(plan.toMeasure <= score.measureCount) { "练习版本范围超出乐谱" }
        val document = MusicXmlScoreParser.parseDocument(xml)
        val differences = when (plan.preset) {
            PracticeVersionPreset.RightHandOnly -> muteOtherHand(document, score, plan, ScoreHand.Right)
            PracticeVersionPreset.LeftHandOnly -> muteOtherHand(document, score, plan, ScoreHand.Left)
            PracticeVersionPreset.ReducedReach -> reduceReach(document, score, plan)
        }
        require(differences.isNotEmpty()) { "当前范围没有需要生成的变化" }

        val revisedXml = document.toPracticeMusicXml()
        val revisedScore = MusicXmlScoreParser.parse(revisedXml)
        ScoreIrValidator.requireValid(revisedScore)
        return PracticeVersionCompilation(revisedXml, revisedScore, plan, differences)
    }

    fun differencesToJson(differences: List<PracticeDifference>): String = JSONArray().apply {
        differences.forEach { difference ->
            put(
                JSONObject()
                    .put("eventId", difference.eventId)
                    .put("measureIndex", difference.measureIndex)
                    .put("kind", difference.kind.code)
                    .put("before", difference.before)
                    .put("after", difference.after)
                    .put("explanation", difference.explanation),
            )
        }
    }.toString()

    fun differencesFromJson(json: String): List<PracticeDifference> = JSONArray(json).let { array ->
        List(array.length()) { index ->
            array.getJSONObject(index).let { item ->
                PracticeDifference(
                    eventId = item.getString("eventId"),
                    measureIndex = item.getInt("measureIndex"),
                    kind = PracticeDifferenceKind.fromCode(item.getString("kind")),
                    before = item.getString("before"),
                    after = item.getString("after"),
                    explanation = item.getString("explanation"),
                )
            }
        }
    }

    private fun muteOtherHand(
        document: Document,
        score: ScoreIr,
        plan: PracticeEditPlan,
        keptHand: ScoreHand,
    ): List<PracticeDifference> {
        val targets = score.events.filter {
            it.measureIndex in plan.fromMeasure..plan.toMeasure &&
                !it.isRest && it.pitch != null && it.hand != keptHand && it.hand != ScoreHand.Unknown
        }
        require(targets.isNotEmpty()) { "当前范围没有可静音的另一只手" }
        val noteElements = targets.associateWith { document.findPracticeNote(it) }
        val groups = targets.groupBy { it.simultaneousKey() }
        groups.values.forEach { group ->
            val ordered = group.sortedBy(ScoreEventIr::noteIndex)
            val primary = ordered.firstOrNull { !it.isChordTone } ?: ordered.first()
            noteElements.getValue(primary).convertToPracticeRest(document)
            ordered.filterNot { it.id == primary.id }.forEach { event ->
                noteElements.getValue(event).let { it.parentNode.removeChild(it) }
            }
        }
        val mutedLabel = if (keptHand == ScoreHand.Right) "左手" else "右手"
        return targets.map { event ->
            PracticeDifference(
                eventId = event.id,
                measureIndex = event.measureIndex,
                kind = PracticeDifferenceKind.Muted,
                before = event.pitch?.displayName ?: "音符",
                after = "静音",
                explanation = "${mutedLabel}在分手练习版中改为保持时值的休止，便于看清时间位置；主谱不变。",
            )
        }
    }

    private fun reduceReach(
        document: Document,
        score: ScoreIr,
        plan: PracticeEditPlan,
    ): List<PracticeDifference> {
        val rangeEvents = score.events.filter { it.measureIndex in plan.fromMeasure..plan.toMeasure }
        val notes = rangeEvents.filter { !it.isRest && it.pitch != null }
        val elements = notes.associateWith { document.findPracticeNote(it) }
        val differences = mutableListOf<PracticeDifference>()

        val chordGroups = notes.groupBy { it.simultaneousKey() }.values.filter { it.size > 1 }
        val chordEventIds = chordGroups.flatten().mapTo(hashSetOf(), ScoreEventIr::id)
        findOctaveShifts(notes.filterNot { it.id in chordEventIds }, plan.maxLeapSemitones).forEach { shift ->
            val note = elements.getValue(shift.event)
            val octave = note.firstDirectChild("pitch")?.firstDirectChild("octave")
                ?: error("目标音符缺少 octave")
            octave.textContent = shift.target.octave.toString()
            differences += PracticeDifference(
                eventId = shift.event.id,
                measureIndex = shift.event.measureIndex,
                kind = PracticeDifferenceKind.OctaveShift,
                before = requireNotNull(shift.event.pitch).displayName,
                after = shift.target.displayName,
                explanation = "保持音级不变，以八度等价位置减小同手连续落点的距离。",
            )
        }

        chordGroups.forEach { group ->
            val pitches = group.mapNotNull { it.pitch?.midi }
            val span = pitches.max() - pitches.min()
            if (group.size <= plan.maxChordNotes && span <= plan.maxChordSpanSemitones) return@forEach
            val anchor = when (group.first().hand) {
                ScoreHand.Left -> group.minBy { requireNotNull(it.pitch).midi }
                else -> group.maxBy { requireNotNull(it.pitch).midi }
            }
            val anchorMidi = requireNotNull(anchor.pitch).midi
            val kept = group
                .filter { abs(requireNotNull(it.pitch).midi - anchorMidi) <= plan.maxChordSpanSemitones }
                .sortedWith(compareBy<ScoreEventIr> { abs(requireNotNull(it.pitch).midi - anchorMidi) }.thenBy(ScoreEventIr::noteIndex))
                .take(plan.maxChordNotes)
                .ifEmpty { listOf(anchor) }
                .sortedBy(ScoreEventIr::noteIndex)
            val keptIds = kept.mapTo(hashSetOf(), ScoreEventIr::id)
            group.filterNot { it.id in keptIds }.forEach { removed ->
                elements.getValue(removed).let { it.parentNode.removeChild(it) }
                differences += PracticeDifference(
                    eventId = removed.id,
                    measureIndex = removed.measureIndex,
                    kind = PracticeDifferenceKind.RemovedChordTone,
                    before = removed.pitch?.displayName ?: "和弦音",
                    after = "移除",
                    explanation = "保留当前手的旋律或低音锚点，并减少同时落键的音数与跨度。",
                )
            }
            kept.forEachIndexed { index, event ->
                val note = elements.getValue(event)
                val chord = note.firstDirectChild("chord")
                if (index == 0) {
                    chord?.let(note::removeChild)
                } else if (chord == null) {
                    val created = document.createElement("chord")
                    note.insertBefore(created, note.firstDirectChild("pitch") ?: note.firstChild)
                }
            }
        }
        return differences
    }

    private fun findOctaveShifts(events: List<ScoreEventIr>, maxLeap: Int): List<OctaveShift> = events
        .filterNot(ScoreEventIr::isChordTone)
        .groupBy { listOf(it.partIndex, it.voice, it.staff ?: -1, it.hand.ordinal) }
        .values
        .flatMap { voice ->
            val shifts = mutableListOf<OctaveShift>()
            var previousMidi: Int? = null
            voice.sortedWith(compareBy<ScoreEventIr> { it.measureIndex }.thenBy { it.onsetDivisions }).forEach { event ->
                val pitch = event.pitch ?: return@forEach
                val previous = previousMidi
                if (previous == null || abs(pitch.midi - previous) <= maxLeap) {
                    previousMidi = pitch.midi
                } else {
                    val candidates = (-5..5).mapNotNull { octaveOffset ->
                        val targetMidi = pitch.midi + octaveOffset * 12
                        if (targetMidi in 21..108) pitch.copy(octave = pitch.octave + octaveOffset) else null
                    }
                    val target = candidates.minWithOrNull(
                        compareBy<ScorePitch> { abs(it.midi - previous) }.thenBy { abs(it.midi - pitch.midi) },
                    ) ?: pitch
                    if (target != pitch) shifts += OctaveShift(event, target)
                    previousMidi = target.midi
                }
            }
            shifts
        }

    private fun ScoreEventIr.simultaneousKey(): List<Any> =
        listOf(partIndex, measureIndex, onsetDivisions, voice, staff ?: -1, hand.ordinal)

    private fun Document.findPracticeNote(event: ScoreEventIr): Element {
        val part = documentElement.directChildren("part").getOrNull(event.partIndex)
            ?: error("找不到声部 ${event.partIndex + 1}")
        val measure = part.directChildren("measure").getOrNull(event.measureIndex - 1)
            ?: error("找不到第 ${event.measureIndex} 小节")
        return measure.directChildren("note").getOrNull(event.noteIndex - 1)
            ?: error("找不到事件 ${event.id}")
    }

    private fun Element.convertToPracticeRest(document: Document) {
        firstDirectChild("pitch")?.let(::removeChild)
        firstDirectChild("unpitched")?.let(::removeChild)
        firstDirectChild("chord")?.let(::removeChild)
        firstDirectChild("accidental")?.let(::removeChild)
        directChildren("tie").forEach(::removeChild)
        firstDirectChild("notations")?.let(::removeChild)
        if (firstDirectChild("rest") == null) {
            val rest = document.createElement("rest")
            insertBefore(rest, firstDirectChild("duration") ?: firstChild)
        }
        removeAttribute("print-object")
    }

    private fun Document.toPracticeMusicXml(): String {
        val transformer = TransformerFactory.newInstance().newTransformer().apply {
            setOutputProperty(OutputKeys.ENCODING, "UTF-8")
            setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "no")
            setOutputProperty(OutputKeys.INDENT, "yes")
            doctype?.publicId?.let { setOutputProperty(OutputKeys.DOCTYPE_PUBLIC, it) }
            doctype?.systemId?.let { setOutputProperty(OutputKeys.DOCTYPE_SYSTEM, it) }
            runCatching { setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "2") }
        }
        return StringWriter().also { writer ->
            transformer.transform(DOMSource(this), StreamResult(writer))
        }.toString()
    }

    private data class OctaveShift(val event: ScoreEventIr, val target: ScorePitch)
}
