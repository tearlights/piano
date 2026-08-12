package com.gpiano.app.midi

data class MidiNoteMessage(
    val channel: Int,
    val pitch: Int,
    val velocity: Int,
    val noteOn: Boolean,
)

/** Parses fragmented channel messages and MIDI running status. */
class MidiMessageParser {
    private var runningStatus: Int? = null
    private val data = ArrayList<Int>(2)

    fun feed(bytes: ByteArray, offset: Int = 0, count: Int = bytes.size - offset): List<MidiNoteMessage> {
        require(offset >= 0 && count >= 0 && offset + count <= bytes.size) { "MIDI 字节范围无效" }
        val messages = mutableListOf<MidiNoteMessage>()
        for (index in offset until offset + count) {
            val value = bytes[index].toInt() and 0xFF
            when {
                value >= 0xF8 -> Unit // Real-time messages may be interleaved anywhere.
                value >= 0xF0 -> {
                    runningStatus = null
                    data.clear()
                }
                value >= 0x80 -> {
                    runningStatus = value
                    data.clear()
                }
                else -> {
                    val status = runningStatus ?: continue
                    data += value
                    val required = if ((status and 0xF0) in setOf(0xC0, 0xD0)) 1 else 2
                    if (data.size == required) {
                        val command = status and 0xF0
                        if (command == 0x80 || command == 0x90) {
                            val velocity = data[1]
                            messages += MidiNoteMessage(
                                channel = status and 0x0F,
                                pitch = data[0],
                                velocity = velocity,
                                noteOn = command == 0x90 && velocity > 0,
                            )
                        }
                        data.clear()
                    }
                }
            }
        }
        return messages
    }

    fun reset() {
        runningStatus = null
        data.clear()
    }
}
