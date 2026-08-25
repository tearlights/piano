package com.gpiano.app.midi

import android.content.Context
import android.media.midi.MidiDevice
import android.media.midi.MidiDeviceInfo
import android.media.midi.MidiManager
import android.media.midi.MidiOutputPort
import android.media.midi.MidiReceiver
import android.os.Handler
import android.os.Looper
import java.io.Closeable

data class MidiInputDevice(
    val id: Int,
    val name: String,
    val outputPort: Int,
)

enum class MidiConnectionState {
    Disconnected,
    Connecting,
    Connected,
    Recording,
}

data class MidiCaptureUiState(
    val devices: List<MidiInputDevice> = emptyList(),
    val selectedDeviceId: Int? = null,
    val selectedDeviceName: String? = null,
    val connection: MidiConnectionState = MidiConnectionState.Disconnected,
    val capturedNoteCount: Int = 0,
    val error: String? = null,
    val screenTest: Boolean = false,
    val captureInterrupted: Boolean = false,
)

class MidiPracticeController(context: Context) : Closeable {
    var onStateChanged: (MidiCaptureUiState) -> Unit = {}
    var onPerformedNotesChanged: (List<PerformedMidiNote>) -> Unit = {}

    private val manager: MidiManager? = context.applicationContext.getSystemService(MidiManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private val parser = MidiMessageParser()
    private val lock = Any()
    private val completeNotes = mutableListOf<PerformedMidiNote>()
    private val activeNotes = linkedMapOf<Pair<Int, Int>, ArrayDeque<PendingNote>>()
    private var nextSequence = 0
    private var recordingOriginNanos: Long? = null
    private var device: MidiDevice? = null
    private var outputPort: MidiOutputPort? = null
    @Volatile
    private var state = MidiCaptureUiState()
    private var closed = false

    fun currentState(): MidiCaptureUiState = state

    fun recordingElapsedNanos(nowNanos: Long = System.nanoTime()): Long = synchronized(lock) {
        recordingOriginNanos?.let { (nowNanos - it).coerceAtLeast(0L) } ?: 0L
    }

    fun performedNotesSnapshot(nowNanos: Long = System.nanoTime()): List<PerformedMidiNote> = synchronized(lock) {
        performedNotesSnapshotLocked(nowNanos)
    }

    private val receiver = object : MidiReceiver() {
        override fun onSend(data: ByteArray, offset: Int, count: Int, timestamp: Long) {
            val receivedAt = timestamp.takeIf { it > 0 } ?: System.nanoTime()
            parser.feed(data, offset, count).forEach { message -> handleMessage(message, receivedAt) }
        }
    }

    private val deviceCallback = object : MidiManager.DeviceCallback() {
        override fun onDeviceAdded(info: MidiDeviceInfo) = refreshDevices()
        override fun onDeviceRemoved(info: MidiDeviceInfo) {
            if (info.id == state.selectedDeviceId) {
                val wasRecording = state.connection == MidiConnectionState.Recording
                if (wasRecording) synchronized(lock) { finishActiveNotes(System.nanoTime()) }
                closeDevice()
                publish(
                    state.copy(
                        connection = MidiConnectionState.Disconnected,
                        selectedDeviceId = null,
                        capturedNoteCount = synchronized(lock) { completeNotes.size },
                        error = if (wasRecording) "MIDI 设备已断开；已保留本次采集的按键" else "MIDI 设备已断开",
                        captureInterrupted = wasRecording,
                    ),
                )
            }
            refreshDevices()
        }
    }

    init {
        if (manager == null) {
            publish(state.copy(error = "这台设备不支持 Android MIDI"))
        } else {
            manager.registerDeviceCallback(deviceCallback, handler)
            refreshDevices()
        }
    }

    fun refreshDevices() {
        val devices = manager?.devices.orEmpty().flatMap { info ->
            info.ports
                .filter { it.type == MidiDeviceInfo.PortInfo.TYPE_OUTPUT }
                .map { port ->
                    MidiInputDevice(info.id, deviceName(info), port.portNumber)
                }
        }.distinctBy { it.id to it.outputPort }.sortedBy(MidiInputDevice::name)
        publish(state.copy(devices = devices))
    }

    fun connect(input: MidiInputDevice) {
        if (state.connection == MidiConnectionState.Recording) return
        val midiManager = manager ?: run {
            publish(state.copy(error = "这台设备不支持 Android MIDI"))
            return
        }
        closeDevice()
        publish(
            state.copy(
                selectedDeviceId = input.id,
                selectedDeviceName = input.name,
                connection = MidiConnectionState.Connecting,
                error = null,
                screenTest = false,
                captureInterrupted = false,
            ),
        )
        val info = midiManager.devices.firstOrNull { it.id == input.id }
        if (info == null) {
            publish(state.copy(connection = MidiConnectionState.Disconnected, error = "MIDI 设备已不可用"))
            return
        }
        midiManager.openDevice(info, { opened ->
            if (closed) {
                opened?.close()
                return@openDevice
            }
            if (opened == null) {
                publish(state.copy(connection = MidiConnectionState.Disconnected, error = "无法打开 MIDI 设备"))
                return@openDevice
            }
            val port = opened.openOutputPort(input.outputPort)
            if (port == null) {
                opened.close()
                publish(state.copy(connection = MidiConnectionState.Disconnected, error = "无法读取 MIDI 输出端口"))
                return@openDevice
            }
            device = opened
            outputPort = port
            port.connect(receiver)
            parser.reset()
            publish(state.copy(connection = MidiConnectionState.Connected, error = null))
        }, handler)
    }

    fun useScreenTestInput() {
        if (state.connection == MidiConnectionState.Recording) return
        closeDevice()
        publish(
            state.copy(
                selectedDeviceId = null,
                selectedDeviceName = "屏幕测试输入",
                connection = MidiConnectionState.Connected,
                error = null,
                screenTest = true,
                captureInterrupted = false,
            ),
        )
    }

    fun startRecording() {
        check(state.connection == MidiConnectionState.Connected) { "请先连接 MIDI 键盘或选择屏幕测试输入" }
        synchronized(lock) {
            completeNotes.clear()
            activeNotes.clear()
            nextSequence = 0
            recordingOriginNanos = System.nanoTime()
        }
        publish(
            state.copy(
                connection = MidiConnectionState.Recording,
                capturedNoteCount = 0,
                error = null,
                captureInterrupted = false,
            ),
        )
    }

    fun injectScreenNote(pitch: Int, velocity: Int = 80) {
        check(state.screenTest && state.connection == MidiConnectionState.Recording) { "屏幕测试输入尚未开始" }
        val now = System.nanoTime()
        handleMessage(MidiNoteMessage(0, pitch, velocity, noteOn = true), now)
        handleMessage(MidiNoteMessage(0, pitch, 0, noteOn = false), now + 120_000_000L)
    }

    fun stopRecording(): List<PerformedMidiNote> {
        check(state.connection == MidiConnectionState.Recording || state.captureInterrupted) { "当前没有可完成的跟弹" }
        synchronized(lock) { finishActiveNotes(System.nanoTime()) }
        val result = synchronized(lock) { completeNotes.sortedBy(PerformedMidiNote::sequence).toList() }
        publish(
            state.copy(
                connection = if (state.captureInterrupted) MidiConnectionState.Disconnected else MidiConnectionState.Connected,
                capturedNoteCount = result.size,
                captureInterrupted = false,
            ),
        )
        return result
    }

    fun cancelRecording() {
        synchronized(lock) {
            completeNotes.clear()
            activeNotes.clear()
            nextSequence = 0
            recordingOriginNanos = null
        }
        publish(
            state.copy(
                connection = if (state.selectedDeviceId != null || state.screenTest) {
                    MidiConnectionState.Connected
                } else {
                    MidiConnectionState.Disconnected
                },
                capturedNoteCount = 0,
                captureInterrupted = false,
            ),
        )
    }

    override fun close() {
        closed = true
        manager?.unregisterDeviceCallback(deviceCallback)
        closeDevice()
        onPerformedNotesChanged = {}
    }

    private fun handleMessage(message: MidiNoteMessage, timestampNanos: Long) {
        if (state.connection != MidiConnectionState.Recording) return
        synchronized(lock) {
            val origin = recordingOriginNanos ?: timestampNanos.also { recordingOriginNanos = it }
            val relative = (timestampNanos - origin).coerceAtLeast(0L)
            val key = message.channel to message.pitch
            if (message.noteOn) {
                activeNotes.getOrPut(key, ::ArrayDeque).addLast(
                    PendingNote(nextSequence++, message.channel, message.pitch, message.velocity, relative),
                )
            } else {
                val pending = activeNotes[key]?.removeFirstOrNull()
                if (pending != null) {
                    completeNotes += pending.complete((relative - pending.onsetNanos).coerceAtLeast(0L))
                    if (activeNotes[key].isNullOrEmpty()) activeNotes.remove(key)
                }
            }
            val count = nextSequence
            val snapshot = performedNotesSnapshotLocked(timestampNanos)
            handler.post {
                publish(state.copy(capturedNoteCount = count))
                onPerformedNotesChanged(snapshot)
            }
        }
    }

    private fun finishActiveNotes(nowNanos: Long) {
        val origin = recordingOriginNanos ?: nowNanos
        val relativeNow = (nowNanos - origin).coerceAtLeast(0L)
        activeNotes.values.flatten().forEach { pending ->
            completeNotes += pending.complete((relativeNow - pending.onsetNanos).coerceAtLeast(0L))
        }
        activeNotes.clear()
    }

    private fun performedNotesSnapshotLocked(nowNanos: Long): List<PerformedMidiNote> {
        val origin = recordingOriginNanos ?: nowNanos
        val relativeNow = (nowNanos - origin).coerceAtLeast(0L)
        return (completeNotes + activeNotes.values.flatten().map { pending ->
            pending.complete((relativeNow - pending.onsetNanos).coerceAtLeast(0L))
        }).sortedBy(PerformedMidiNote::sequence)
    }

    private fun closeDevice() {
        runCatching { outputPort?.disconnect(receiver) }
        runCatching { outputPort?.close() }
        runCatching { device?.close() }
        outputPort = null
        device = null
        parser.reset()
    }

    private fun publish(next: MidiCaptureUiState) {
        state = next
        if (Looper.myLooper() == Looper.getMainLooper()) onStateChanged(next) else handler.post { onStateChanged(next) }
    }

    private fun deviceName(info: MidiDeviceInfo): String {
        val properties = info.properties
        return listOfNotNull(
            properties.getString(MidiDeviceInfo.PROPERTY_NAME),
            properties.getString(MidiDeviceInfo.PROPERTY_PRODUCT),
            properties.getString(MidiDeviceInfo.PROPERTY_MANUFACTURER),
        ).firstOrNull { it.isNotBlank() } ?: "MIDI 设备 ${info.id}"
    }

    private data class PendingNote(
        val sequence: Int,
        val channel: Int,
        val pitch: Int,
        val velocity: Int,
        val onsetNanos: Long,
    ) {
        fun complete(durationNanos: Long) = PerformedMidiNote(
            sequence,
            channel,
            pitch,
            velocity,
            onsetNanos,
            durationNanos,
        )
    }
}
