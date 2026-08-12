package com.gpiano.app.scoreworkspace

import java.io.StringWriter
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult
import org.w3c.dom.Document
import org.w3c.dom.Element

sealed interface CorrectionOperation {
    val eventId: String

    data class ChangePitch(
        override val eventId: String,
        val pitch: ScorePitch,
    ) : CorrectionOperation

    data class ChangeDuration(
        override val eventId: String,
        val duration: MusicalDuration,
    ) : CorrectionOperation

    data class ChangeRest(
        override val eventId: String,
        val makeRest: Boolean,
        val pitchWhenNote: ScorePitch? = null,
    ) : CorrectionOperation

    data class SetTieToNext(
        override val eventId: String,
        val enabled: Boolean,
    ) : CorrectionOperation
}

data class MusicXmlCompilationResult(
    val xml: String,
    val score: ScoreIr,
    val changedEvent: ScoreEventIr,
)

object MusicXmlRevisionCompiler {
    fun apply(xml: String, operation: CorrectionOperation): MusicXmlCompilationResult {
        val originalScore = MusicXmlScoreParser.parse(xml)
        val target = originalScore.findEvent(operation.eventId)
            ?: error("找不到要修改的乐谱元素：${operation.eventId}")
        val document = MusicXmlScoreParser.parseDocument(xml)
        val note = document.findNote(target)

        when (operation) {
            is CorrectionOperation.ChangePitch -> changePitch(document, note, target, operation.pitch)
            is CorrectionOperation.ChangeDuration -> changeDuration(
                document,
                originalScore,
                target,
                operation.duration,
            )
            is CorrectionOperation.ChangeRest -> changeRest(document, originalScore, target, operation)
            is CorrectionOperation.SetTieToNext -> setTieToNext(document, originalScore, target, operation.enabled)
        }

        val revisedXml = document.toMusicXml()
        val revisedScore = MusicXmlScoreParser.parse(revisedXml)
        val revisedEvent = revisedScore.findEvent(operation.eventId)
            ?: error("修改后无法重新定位乐谱元素：${operation.eventId}")
        when (operation) {
            is CorrectionOperation.ChangePitch -> check(revisedEvent.pitch == operation.pitch) {
                "音高修改校验失败：期望 ${operation.pitch.displayName}，实际 ${revisedEvent.pitch?.displayName}"
            }
            is CorrectionOperation.ChangeDuration -> {
                val divisions = revisedScore.parts[target.partIndex].measures[target.measureIndex - 1].divisions
                check(revisedEvent.durationDivisions == operation.duration.toDivisions(divisions)) {
                    "时值修改校验失败"
                }
            }
            is CorrectionOperation.ChangeRest -> check(revisedEvent.isRest == operation.makeRest) {
                "音符/休止符修改校验失败"
            }
            is CorrectionOperation.SetTieToNext -> check(revisedEvent.tieStart == operation.enabled) {
                "延音线修改校验失败"
            }
        }
        return MusicXmlCompilationResult(revisedXml, revisedScore, revisedEvent)
    }

    private fun changePitch(
        document: Document,
        note: Element,
        target: ScoreEventIr,
        pitch: ScorePitch,
    ) {
        require(!target.isRest) { "休止符不能修改音高" }
        require(pitch.midi in 0..127) { "音高超出钢琴与 MIDI 支持范围" }
        val pitchElement = note.firstDirectChild("pitch") ?: error("目标音符缺少 pitch 节点")
        pitchElement.requireChild("step").textContent = pitch.step.toString()
        pitchElement.requireChild("octave").textContent = pitch.octave.toString()

        val alterElement = pitchElement.firstDirectChild("alter")
        if (pitch.alter == 0) {
            alterElement?.let(pitchElement::removeChild)
        } else if (alterElement != null) {
            alterElement.textContent = pitch.alter.toString()
        } else {
            val created = document.createElement("alter").apply { textContent = pitch.alter.toString() }
            val octaveElement = pitchElement.requireChild("octave")
            pitchElement.insertBefore(created, octaveElement)
        }
    }

    private fun changeDuration(
        document: Document,
        score: ScoreIr,
        target: ScoreEventIr,
        duration: MusicalDuration,
    ) {
        val measure = score.parts[target.partIndex].measures[target.measureIndex - 1]
        val value = duration.toDivisions(measure.divisions)
        val chord = measure.events.filter {
            it.onsetDivisions == target.onsetDivisions && it.voice == target.voice && it.staff == target.staff
        }
        chord.forEach { event ->
            val note = document.findNote(event)
            note.requireChild("duration").textContent = value.toString()
            val type = note.firstDirectChild("type") ?: document.createElement("type").also { created ->
                val anchor = note.directChildren().firstOrNull {
                    it.tagName in setOf("dot", "accidental", "time-modification", "stem", "notehead", "staff", "beam", "notations", "lyric")
                }
                note.insertBefore(created, anchor)
            }
            type.textContent = duration.noteType
            note.directChildren("dot").forEach(note::removeChild)
            var dotAnchor: org.w3c.dom.Node = type
            repeat(duration.dots) {
                val dot = document.createElement("dot")
                note.insertBefore(dot, dotAnchor.nextSibling)
                dotAnchor = dot
            }
            val existingModification = note.firstDirectChild("time-modification")
            if (duration.actualNotes == null || duration.normalNotes == null) {
                existingModification?.let(note::removeChild)
            } else {
                val modification = existingModification ?: document.createElement("time-modification").also { created ->
                    val anchor = note.directChildren().firstOrNull {
                        it.tagName in setOf("stem", "notehead", "staff", "beam", "notations", "lyric")
                    }
                    note.insertBefore(created, anchor)
                }
                modification.setTextChild(document, "actual-notes", duration.actualNotes.toString())
                modification.setTextChild(document, "normal-notes", duration.normalNotes.toString())
            }
        }
    }

    private fun changeRest(
        document: Document,
        score: ScoreIr,
        target: ScoreEventIr,
        operation: CorrectionOperation.ChangeRest,
    ) {
        val measure = score.parts[target.partIndex].measures[target.measureIndex - 1]
        val simultaneous = measure.events.filter {
            it.onsetDivisions == target.onsetDivisions && it.voice == target.voice && it.staff == target.staff
        }
        require(simultaneous.size == 1 && !target.isChordTone) { "和弦中的单个音暂不能直接改为休止符" }
        val note = document.findNote(target)
        if (operation.makeRest) {
            require(!target.isRest) { "当前已经是休止符" }
            val pitch = note.requireChild("pitch")
            note.insertBefore(document.createElement("rest"), pitch)
            note.removeChild(pitch)
            note.firstDirectChild("accidental")?.let(note::removeChild)
            note.directChildren("tie").forEach(note::removeChild)
            note.firstDirectChild("notations")?.let { notations ->
                notations.directChildren("tied").forEach(notations::removeChild)
                if (notations.directChildren().isEmpty()) note.removeChild(notations)
            }
        } else {
            require(target.isRest) { "当前不是休止符" }
            val pitch = operation.pitchWhenNote ?: error("将休止符改为音符时需要指定音高")
            val rest = note.requireChild("rest")
            note.insertBefore(document.createPitch(pitch), rest)
            note.removeChild(rest)
        }
    }

    private fun setTieToNext(
        document: Document,
        score: ScoreIr,
        target: ScoreEventIr,
        enabled: Boolean,
    ) {
        require(!target.isRest && target.pitch != null) { "休止符不能连接延音线" }
        val partEvents = score.parts[target.partIndex].measures.flatMap(ScoreMeasureIr::events)
        val future = partEvents.filter {
            it.voice == target.voice && it.staff == target.staff &&
                (it.measureIndex > target.measureIndex ||
                    it.measureIndex == target.measureIndex && it.onsetDivisions > target.onsetDivisions)
        }
        val nextOnset = future.minWithOrNull(compareBy<ScoreEventIr> { it.measureIndex }.thenBy { it.onsetDivisions })
            ?: error("后面没有可连接的音符")
        val next = future.firstOrNull {
            it.measureIndex == nextOnset.measureIndex && it.onsetDivisions == nextOnset.onsetDivisions && it.pitch == target.pitch
        } ?: error("下一个发音位置没有相同音高，不能添加延音线")
        val startNote = document.findNote(target)
        val stopNote = document.findNote(next)
        if (enabled) {
            startNote.ensureDirectTie(document, "start")
            stopNote.ensureDirectTie(document, "stop")
            startNote.ensureNotationTie(document, "start")
            stopNote.ensureNotationTie(document, "stop")
        } else {
            startNote.removeTie("tie", "start")
            stopNote.removeTie("tie", "stop")
            startNote.removeNotationTie("start")
            stopNote.removeNotationTie("stop")
        }
    }

    private fun Document.findNote(event: ScoreEventIr): Element {
        val root = documentElement ?: error("MusicXML 缺少根节点")
        val part = root.directChildren("part").getOrNull(event.partIndex)
            ?: error("找不到声部 ${event.partIndex + 1}")
        val measure = part.directChildren("measure").getOrNull(event.measureIndex - 1)
            ?: error("找不到第 ${event.measureIndex} 小节")
        return measure.directChildren("note").getOrNull(event.noteIndex - 1)
            ?: error("找不到第 ${event.noteIndex} 个音符")
    }

    private fun Element.requireChild(tagName: String): Element =
        firstDirectChild(tagName) ?: error("${this.tagName} 缺少 $tagName 节点")

    private fun Element.setTextChild(document: Document, tagName: String, value: String) {
        (firstDirectChild(tagName) ?: document.createElement(tagName).also(::appendChild)).textContent = value
    }

    private fun Document.createPitch(pitch: ScorePitch): Element = createElement("pitch").apply {
        appendChild(createElement("step").apply { textContent = pitch.step.toString() })
        if (pitch.alter != 0) appendChild(createElement("alter").apply { textContent = pitch.alter.toString() })
        appendChild(createElement("octave").apply { textContent = pitch.octave.toString() })
    }

    private fun Element.ensureDirectTie(document: Document, type: String) {
        if (directChildren("tie").any { it.getAttribute("type") == type }) return
        val tie = document.createElement("tie").apply { setAttribute("type", type) }
        val existingTies = directChildren("tie")
        val anchor = if (type == "stop") existingTies.firstOrNull { it.getAttribute("type") == "start" }
            else firstDirectChild("voice")
        insertBefore(tie, anchor)
    }

    private fun Element.ensureNotationTie(document: Document, type: String) {
        val notations = firstDirectChild("notations") ?: document.createElement("notations").also(::appendChild)
        if (notations.directChildren("tied").any { it.getAttribute("type") == type }) return
        val tied = document.createElement("tied").apply { setAttribute("type", type) }
        val anchor = if (type == "stop") notations.directChildren("tied")
            .firstOrNull { it.getAttribute("type") == "start" } else null
        notations.insertBefore(tied, anchor)
    }

    private fun Element.removeTie(tagName: String, type: String) {
        directChildren(tagName).filter { it.getAttribute("type") == type }.forEach(::removeChild)
    }

    private fun Element.removeNotationTie(type: String) {
        firstDirectChild("notations")?.let { notations ->
            notations.removeTie("tied", type)
            if (notations.directChildren().isEmpty()) removeChild(notations)
        }
    }

    private fun Document.toMusicXml(): String {
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
}
