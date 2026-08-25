package com.gpiano.app.midi

import android.content.Context
import com.gpiano.app.data.PracticeAttempt
import com.gpiano.app.data.PracticeAttemptInputKind
import com.gpiano.app.scoreworkspace.PlaybackPlan
import java.io.Closeable

data class MidiPracticeTarget(
    val structureId: String,
    val sourceRevisionId: String,
    val practiceVersionId: String?,
    val plan: PlaybackPlan,
)

data class WorkspaceMidiSessionState(
    val capture: MidiCaptureUiState = MidiCaptureUiState(),
    val target: MidiPracticeTarget? = null,
    val startedAt: Long? = null,
    val busy: Boolean = false,
    val error: String? = null,
    val result: StoredPracticeAttempt? = null,
    val recent: List<PracticeAttempt> = emptyList(),
) {
    val hasUnfinishedRecording: Boolean
        get() = capture.connection == MidiConnectionState.Recording || capture.captureInterrupted
}

/** Owns MIDI resources for as long as the practice workspace is open. */
class WorkspaceMidiSession(context: Context) : Closeable {
    var onStateChanged: (WorkspaceMidiSessionState) -> Unit = {}

    private val controller = MidiPracticeController(context.applicationContext)
    private val repository = PracticeAttemptRepository(context.applicationContext)
    private var state = WorkspaceMidiSessionState(capture = controller.currentState())
    private var closed = false

    init {
        controller.onStateChanged = { capture -> publish(state.copy(capture = capture)) }
    }

    fun currentState(): WorkspaceMidiSessionState = state

    fun configure(target: MidiPracticeTarget) {
        check(!state.hasUnfinishedRecording) { "请先完成或取消当前跟弹" }
        publish(state.copy(target = target, error = null, result = null))
    }

    fun refreshDevices() = controller.refreshDevices()

    fun connect(input: MidiInputDevice) = controller.connect(input)

    fun useScreenTestInput() = controller.useScreenTestInput()

    fun startRecording() {
        checkNotNull(state.target) { "当前没有可跟弹的选段" }
        controller.startRecording()
        publish(state.copy(startedAt = System.currentTimeMillis(), error = null, result = null))
    }

    fun injectScreenNote(pitch: Int) = controller.injectScreenNote(pitch)

    suspend fun finishRecording(): StoredPracticeAttempt? {
        if (state.busy) return null
        val target = checkNotNull(state.target) { "当前没有可跟弹的选段" }
        val captureStartedAt = checkNotNull(state.startedAt) { "当前没有可完成的跟弹" }
        val inputSnapshot = state.capture
        publish(state.copy(busy = true, error = null))
        return runCatching {
            val notes = controller.stopRecording()
            val report = PerformanceMatcher.match(target.plan, notes)
            repository.save(
                structureId = target.structureId,
                sourceRevisionId = target.sourceRevisionId,
                practiceVersionId = target.practiceVersionId,
                plan = target.plan,
                inputKind = if (inputSnapshot.screenTest) {
                    PracticeAttemptInputKind.ScreenTest
                } else {
                    PracticeAttemptInputKind.Midi
                },
                deviceId = inputSnapshot.selectedDeviceId,
                deviceName = inputSnapshot.selectedDeviceName ?: "未知 MIDI 设备",
                startedAt = captureStartedAt,
                finishedAt = System.currentTimeMillis(),
                performed = notes,
                report = report,
            )
        }.fold(
            onSuccess = { saved ->
                val recent = repository.recent(target.structureId)
                publish(state.copy(startedAt = null, busy = false, result = saved, recent = recent))
                saved
            },
            onFailure = { failure ->
                publish(state.copy(busy = false, error = failure.message ?: "无法保存跟弹结果"))
                null
            },
        )
    }

    fun cancelRecording() {
        controller.cancelRecording()
        publish(state.copy(startedAt = null, error = null, result = null))
    }

    fun clearResult() = publish(state.copy(result = null, error = null))

    suspend fun loadRecent(structureId: String) {
        runCatching { repository.recent(structureId) }
            .onSuccess { publish(state.copy(recent = it)) }
            .onFailure { publish(state.copy(error = it.message ?: "无法读取最近跟弹")) }
    }

    suspend fun loadAttempt(id: String) {
        if (state.busy) return
        publish(state.copy(busy = true, error = null))
        runCatching { repository.load(id) }
            .onSuccess { publish(state.copy(busy = false, result = it)) }
            .onFailure { publish(state.copy(busy = false, error = it.message ?: "无法读取跟弹记录")) }
    }

    fun reportError(message: String) = publish(state.copy(error = message))

    override fun close() {
        if (closed) return
        closed = true
        if (state.hasUnfinishedRecording) controller.cancelRecording()
        controller.close()
        onStateChanged = {}
    }

    private fun publish(next: WorkspaceMidiSessionState) {
        if (closed) return
        state = next
        onStateChanged(next)
    }
}
