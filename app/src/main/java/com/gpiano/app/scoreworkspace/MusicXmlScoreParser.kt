package com.gpiano.app.scoreworkspace

import java.io.StringReader
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilder
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.InputSource

object MusicXmlScoreParser {
    fun parse(xml: String): ScoreIr = parseDocument(xml).toScoreIr()

    internal fun parseDocument(xml: String): Document {
        require(xml.isNotBlank()) { "MusicXML 内容为空" }
        rejectUnsafeDeclarations(xml)
        return newDocumentBuilder().parse(InputSource(StringReader(xml)))
    }

    private fun Document.toScoreIr(): ScoreIr {
        val root = documentElement ?: error("MusicXML 缺少根节点")
        require(root.tagName == "score-partwise") { "当前只支持 score-partwise MusicXML" }

        val title = root.firstDirectChild("movement-title")?.textContent?.trim().orEmpty()
            .ifBlank { "未命名练习谱" }
        val partNames = root.firstDirectChild("part-list")
            ?.directChildren("score-part")
            .orEmpty()
            .associate { part ->
                part.getAttribute("id") to part.firstDirectChild("part-name")?.textContent?.trim()
            }
        val partElements = root.directChildren("part")
        var scoreFifths: Int? = null
        var scoreBeats: Int? = null
        var scoreBeatType: Int? = null
        var scoreTempo: Double? = root.firstDescendantText("per-minute")?.toDoubleOrNull()

        val parts = partElements.mapIndexed { partIndex, partElement ->
            val partId = partElement.getAttribute("id").ifBlank { "part-${partIndex + 1}" }
            val partName = partNames[partId]
            var divisions = 1
            var beats: Int? = null
            var beatType: Int? = null
            val measures = partElement.directChildren("measure").mapIndexed { measureOffset, measureElement ->
                val attributes = measureElement.firstDirectChild("attributes")
                attributes?.firstDirectChild("divisions")?.intTextOrNull()?.let { divisions = it }
                attributes?.firstDirectChild("key")?.firstDirectChild("fifths")?.intTextOrNull()?.let {
                    if (scoreFifths == null) scoreFifths = it
                }
                attributes?.firstDirectChild("time")?.let { time ->
                    time.firstDirectChild("beats")?.intTextOrNull()?.let { beats = it }
                    time.firstDirectChild("beat-type")?.intTextOrNull()?.let { beatType = it }
                    if (scoreBeats == null) scoreBeats = beats
                    if (scoreBeatType == null) scoreBeatType = beatType
                }

                val measureIndex = measureOffset + 1
                val sourceNumber = measureElement.getAttribute("number").ifBlank { null }
                if (scoreTempo == null) {
                    scoreTempo = measureElement.directChildren("direction")
                        .asSequence()
                        .mapNotNull { it.firstDirectChild("sound")?.getAttribute("tempo")?.toDoubleOrNull() }
                        .firstOrNull()
                }
                var cursor = 0L
                var lastNoteOnset = 0L
                var noteIndex = 0
                val events = mutableListOf<ScoreEventIr>()

                measureElement.directChildren().forEach { child ->
                    when (child.tagName) {
                        "backup" -> cursor -= child.firstDirectChild("duration")?.longTextOrNull() ?: 0L
                        "forward" -> cursor += child.firstDirectChild("duration")?.longTextOrNull() ?: 0L
                        "note" -> {
                            noteIndex += 1
                            val isChordTone = child.firstDirectChild("chord") != null
                            val duration = child.firstDirectChild("duration")?.longTextOrNull() ?: 0L
                            val onset = if (isChordTone) lastNoteOnset else cursor
                            val voice = child.firstDirectChild("voice")?.textContent?.trim().orEmpty().ifBlank { "1" }
                            val staff = child.firstDirectChild("staff")?.intTextOrNull()
                            val isRest = child.firstDirectChild("rest") != null
                            val pitch = child.firstDirectChild("pitch")?.toPitch()
                            val timeModification = child.firstDirectChild("time-modification")
                            val notations = child.firstDirectChild("notations")
                            val directTies = child.directChildren("tie")
                            val notationTies = notations?.directChildren("tied").orEmpty()
                            val slurs = notations?.directChildren("slur").orEmpty()
                            val event = ScoreEventIr(
                                id = eventId(partIndex, measureIndex, noteIndex),
                                partIndex = partIndex,
                                partId = partId,
                                partName = partName,
                                measureIndex = measureIndex,
                                sourceMeasureNumber = sourceNumber,
                                noteIndex = noteIndex,
                                voice = voice,
                                staff = staff,
                                hand = resolveHand(partElements.size, partIndex, staff),
                                onsetDivisions = onset,
                                durationDivisions = duration,
                                isChordTone = isChordTone,
                                isRest = isRest,
                                pitch = pitch,
                                noteType = child.firstDirectChild("type")?.textContent?.trim(),
                                dots = child.directChildren("dot").size,
                                tupletActualNotes = timeModification?.firstDirectChild("actual-notes")?.intTextOrNull(),
                                tupletNormalNotes = timeModification?.firstDirectChild("normal-notes")?.intTextOrNull(),
                                tieStart = (directTies + notationTies).any { it.getAttribute("type") == "start" },
                                tieStop = (directTies + notationTies).any { it.getAttribute("type") == "stop" },
                                slurStart = slurs.any { it.getAttribute("type") == "start" },
                                slurStop = slurs.any { it.getAttribute("type") == "stop" },
                            )
                            events += event
                            if (!isChordTone) {
                                lastNoteOnset = onset
                                cursor += duration
                            }
                        }
                    }
                }

                ScoreMeasureIr(
                    index = measureIndex,
                    sourceNumber = sourceNumber,
                    divisions = divisions,
                    beats = beats,
                    beatType = beatType,
                    events = events,
                )
            }
            ScorePartIr(
                index = partIndex,
                id = partId,
                name = partName,
                measures = measures,
            )
        }

        return ScoreIr(
            title = title,
            tempoBpm = scoreTempo,
            fifths = scoreFifths,
            beats = scoreBeats,
            beatType = scoreBeatType,
            parts = parts,
        ).also { score ->
            val errors = ScoreIrValidator.validate(score)
            require(errors.isEmpty()) { errors.joinToString(separator = "；") }
        }
    }

    internal fun eventId(partIndex: Int, measureIndex: Int, noteIndex: Int): String =
        "part:${partIndex + 1}/measure:$measureIndex/note:$noteIndex"

    private fun Element.toPitch(): ScorePitch = try {
        val stepText = firstDirectChild("step")?.textContent?.trim().orEmpty()
        require(stepText.length == 1) { "step 缺失或无效" }
        val alterElement = firstDirectChild("alter")
        val alter = alterElement?.textContent?.trim()?.toIntOrNull()
            ?: if (alterElement == null) 0 else throw IllegalArgumentException("alter 不是整数")
        val octave = firstDirectChild("octave")?.textContent?.trim()?.toIntOrNull()
            ?: throw IllegalArgumentException("octave 缺失或不是整数")
        ScorePitch(stepText.single().uppercaseChar(), alter, octave)
    } catch (error: IllegalArgumentException) {
        throw IllegalArgumentException("无法解析 MusicXML 音高：${error.message}", error)
    }

    private fun resolveHand(partCount: Int, partIndex: Int, staff: Int?): ScoreHand = when {
        partCount == 2 && partIndex == 0 -> ScoreHand.Right
        partCount == 2 && partIndex == 1 -> ScoreHand.Left
        staff == 1 -> ScoreHand.Right
        staff == 2 -> ScoreHand.Left
        partCount == 1 -> ScoreHand.Right
        else -> ScoreHand.Unknown
    }

    private fun newDocumentBuilder(): DocumentBuilder {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = false
            runCatching { isXIncludeAware = false }
            runCatching { isExpandEntityReferences = false }
            setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
            setFeatureSafely("http://xml.org/sax/features/external-general-entities", false)
            setFeatureSafely("http://xml.org/sax/features/external-parameter-entities", false)
            setFeatureSafely("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
            runCatching { setAttribute(ACCESS_EXTERNAL_DTD, "") }
            runCatching { setAttribute(ACCESS_EXTERNAL_SCHEMA, "") }
        }
        return factory.newDocumentBuilder().apply {
            setEntityResolver { _, _ -> InputSource(StringReader("")) }
        }
    }

    private fun DocumentBuilderFactory.setFeatureSafely(name: String, value: Boolean) {
        runCatching { setFeature(name, value) }
    }

    private fun rejectUnsafeDeclarations(xml: String) {
        require(!UNSAFE_ENTITY.containsMatchIn(xml)) { "MusicXML 不允许实体声明" }
        require(!DOCTYPE_INTERNAL_SUBSET.containsMatchIn(xml)) { "MusicXML 不允许内联 DTD" }
    }

    private val UNSAFE_ENTITY = Regex("<!\\s*ENTITY\\b", RegexOption.IGNORE_CASE)
    private val DOCTYPE_INTERNAL_SUBSET = Regex("<!\\s*DOCTYPE[^>]*\\[", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private const val ACCESS_EXTERNAL_DTD = "http://javax.xml.XMLConstants/property/accessExternalDTD"
    private const val ACCESS_EXTERNAL_SCHEMA = "http://javax.xml.XMLConstants/property/accessExternalSchema"
}

internal fun Element.directChildren(tagName: String? = null): List<Element> = buildList {
    val children = childNodes
    for (index in 0 until children.length) {
        val child = children.item(index)
        if (child.nodeType == Node.ELEMENT_NODE) {
            val element = child as Element
            if (tagName == null || element.tagName == tagName) add(element)
        }
    }
}

internal fun Element.firstDirectChild(tagName: String): Element? =
    directChildren(tagName).firstOrNull()

private fun Element.firstDescendantText(tagName: String): String? {
    val nodes = getElementsByTagName(tagName)
    return if (nodes.length == 0) null else nodes.item(0)?.textContent?.trim()
}

private fun Element.intTextOrNull(): Int? = textContent?.trim()?.toIntOrNull()

private fun Element.longTextOrNull(): Long? = textContent?.trim()?.toLongOrNull()
