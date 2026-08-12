package com.gpiano.app.scoreworkspace

import java.io.ByteArrayOutputStream
import kotlin.math.roundToInt

/** Compiles the selected [PlaybackPlan] to a portable Standard MIDI File (format 0). */
object StandardMidiFile {
    fun encode(plan: PlaybackPlan): ByteArray {
        require(plan.ticksPerQuarter in 1..0x7fff) { "MIDI 每四分音符 tick 数超出格式范围" }
        require(plan.durationTick > 0) { "当前片段没有可导出的时长" }
        val tempo = (plan.tempoBpm * plan.selection.speed).coerceIn(1.0, 960.0)
        val microsPerQuarter = (60_000_000.0 / tempo).roundToInt().coerceIn(1, 0x00ff_ffff)
        val track = ByteArrayOutputStream()
        val events = mutableListOf<MidiTrackEvent>()
        events += MidiTrackEvent(0, EventOrder.Metadata, byteArrayOf(0xff.toByte(), 0x51, 0x03) + threeBytes(microsPerQuarter))
        events += MidiTrackEvent(0, EventOrder.Metadata, byteArrayOf(0xc0.toByte(), 0x00))
        events += MidiTrackEvent(0, EventOrder.Metadata, byteArrayOf(0xc1.toByte(), 0x00))

        mergedNotes(plan).forEach { note ->
            val channel = if (note.hand == ScoreHand.Left) 1 else 0
            events += MidiTrackEvent(
                tick = note.startTick - plan.rangeStartTick,
                order = EventOrder.NoteOn,
                payload = byteArrayOf((0x90 or channel).toByte(), note.pitch.toByte(), DEFAULT_VELOCITY.toByte()),
            )
            events += MidiTrackEvent(
                tick = note.endTick - plan.rangeStartTick,
                order = EventOrder.NoteOff,
                payload = byteArrayOf((0x80 or channel).toByte(), note.pitch.toByte(), 0),
            )
        }
        events.sortWith(compareBy<MidiTrackEvent>(MidiTrackEvent::tick, MidiTrackEvent::order, { it.payload[1].toInt() and 0xff }))

        var previousTick = 0L
        events.forEach { event ->
            require(event.tick >= previousTick) { "MIDI 事件时间顺序无效" }
            track.writeVariableLength(event.tick - previousTick)
            track.write(event.payload)
            previousTick = event.tick
        }
        val endTick = plan.durationTick.coerceAtLeast(previousTick)
        track.writeVariableLength(endTick - previousTick)
        track.write(byteArrayOf(0xff.toByte(), 0x2f, 0x00))

        val bytes = ByteArrayOutputStream()
        bytes.writeAscii("MThd")
        bytes.writeInt32(6)
        bytes.writeInt16(0)
        bytes.writeInt16(1)
        bytes.writeInt16(plan.ticksPerQuarter)
        bytes.writeAscii("MTrk")
        bytes.writeInt32(track.size())
        track.writeTo(bytes)
        return bytes.toByteArray()
    }

    private fun mergedNotes(plan: PlaybackPlan): List<MidiNote> {
        val notes = mutableListOf<MidiNote>()
        plan.events.sortedWith(compareBy(PlaybackEvent::startTick, PlaybackEvent::midiPitch, PlaybackEvent::eventId))
            .forEach { event ->
                val endTick = event.startTick + event.durationTick
                val previous = if (event.tieStop) {
                    notes.lastOrNull {
                        it.pitch == event.midiPitch && it.hand == event.hand && it.canContinue && it.endTick == event.startTick
                    }
                } else {
                    null
                }
                if (previous != null) {
                    previous.endTick = endTick.coerceAtLeast(previous.endTick)
                    previous.canContinue = event.tieStart
                } else {
                    notes += MidiNote(event.startTick, endTick, event.midiPitch, event.hand, event.tieStart)
                }
            }
        return notes
    }

    private fun threeBytes(value: Int): ByteArray = byteArrayOf(
        ((value ushr 16) and 0xff).toByte(),
        ((value ushr 8) and 0xff).toByte(),
        (value and 0xff).toByte(),
    )

    private fun ByteArrayOutputStream.writeVariableLength(value: Long) {
        require(value in 0..0x0fff_ffffL) { "MIDI 事件间隔超出格式范围" }
        var remaining = value
        var buffer = remaining and 0x7f
        while (remaining.also { remaining = it ushr 7 } > 0x7f) {
            buffer = (buffer shl 8) or ((remaining and 0x7f) or 0x80)
        }
        while (true) {
            write((buffer and 0xff).toInt())
            if (buffer and 0x80 == 0L) break
            buffer = buffer ushr 8
        }
    }

    private fun ByteArrayOutputStream.writeAscii(value: String) = write(value.toByteArray(Charsets.US_ASCII))

    private fun ByteArrayOutputStream.writeInt16(value: Int) {
        write((value ushr 8) and 0xff)
        write(value and 0xff)
    }

    private fun ByteArrayOutputStream.writeInt32(value: Int) {
        write((value ushr 24) and 0xff)
        write((value ushr 16) and 0xff)
        write((value ushr 8) and 0xff)
        write(value and 0xff)
    }

    private data class MidiTrackEvent(val tick: Long, val order: EventOrder, val payload: ByteArray)

    private enum class EventOrder {
        Metadata,
        NoteOff,
        NoteOn,
    }

    private data class MidiNote(
        val startTick: Long,
        var endTick: Long,
        val pitch: Int,
        val hand: ScoreHand,
        var canContinue: Boolean,
    )

    private const val DEFAULT_VELOCITY = 80
}
