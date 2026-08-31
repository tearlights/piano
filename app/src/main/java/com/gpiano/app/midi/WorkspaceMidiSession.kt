package com.gpiano.app.midi

import android.content.Context
import com.gpiano.app.data.PracticeAttempt
import com.gpiano.app.data.PracticeAttemptInputKind
import com.gpiano.app.scoreworkspace.PlaybackPlan
import java.io.Closeable
import kotlin.math.ceil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

enum class MidiPracticePhase {
    Idle,
    CountIn,
    Recording,
}

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
    val phase: MidiPracticePhase = MidiPracticePhase.Idle,
    val countInBeat: Int? = null,
    val liveFeedback: LivePerformanceFeedback? = null,
) {
    val hasUnfinishedRecording: Boolean
        get() = phase != MidiPracticePhase.Idle ||
            capture.connection == MidiConnectionState.Recording ||
            capture.captureInterrupted

    val canFinishRecording: Boolean
        get() = phase == MidiPracticePhase.Recording ||
            capture.connection == MidiConnectionState.Recording ||
            capture.captureInterrupted
}

/** Owns MIDI resources for as long as the practice workspace is open. */
class WorkspaceMidiSession(context: Context) : Closeable {
    var onStateChanged: (WorkspaceMidiSessionState) -> Unit = {}

    private val controller = MidiPracticeController(context.applicationContext)
    private val repository = PracticeAttemptRepository(context.applicationContext)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var state = WorkspaceMidiSessionState(capture = controller.currentState())
    private var closed = false
    private var timelineJob: Job? = null
    private var performedNotes: List<PerformedMidiNote> = emptyList()

    init {
        controller.onStateChanged = { capture -> publish(state.copy(capture = capture)) }
        controller.onPerformedNotesChanged = { notes ->
            performedNotes = notes
            publishLiveFeedback()
        }
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
        check(state.capture.connection == MidiConnectionState.Connected) {
            "请先连接 MIDI 键盘或选择屏幕测试输入"
        }
        timelineJob?.cancel()
        performedNotes = emptyList()
        val plan = requireNotNull(state.target).plan
        val firstMeasure = plan.measures.first()
        val countInMillis = PerformanceMatcher.ticksToMillis(
            plan,
            firstMeasure.endTick - firstMeasure.startTick,
        ).coerceAtLeast(1L)
        val beatMillis = (60_000.0 / (plan.tempoBpm * plan.selection.speed)).toLong().coerceAtLeast(1L)
        val beats = ceil(countInMillis.toDouble() / beatMillis).toInt().coerceAtLeast(1)
        publish(
            state.copy(
                startedAt = null,
                error = null,
                result = null,
                phase = MidiPracticePhase.CountIn,
                countInBeat = beats,
                liveFeedback = null,
            ),
        )
        timelineJob = scope.launch {
            val countInStarted = System.nanoTime()
            while (isActive) {
                val elapsed = (System.nanoTime() - countInStarted) / 1_000_000L
                if (elapsed >= countInMillis) break
                val remaining = (countInMillis - elapsed).coerceAtLeast(1L)
                val beat = ceil(remaining.toDouble() / beatMillis).toInt().coerceIn(1, beats)
                if (state.countInBeat != beat) publish(state.copy(countInBeat = beat))
                delay(40L)
            }
            runCatching { controller.startRecording() }
                .onSuccess {
                    publish(
                        state.copy(
                            startedAt = System.currentTimeMillis(),
                            phase = MidiPracticePhase.Recording,
                            countInBeat = null,
                            liveFeedback = IncrementalPerformanceMatcher.evaluate(plan, emptyList(), 0L),
                        ),
                    )
                    while (isActive && state.phase == MidiPracticePhase.Recording) {
                        publishLiveFeedback()
                        delay(80L)
                    }
                }
                .onFailure {
                    publish(
                        state.copy(
                            phase = MidiPracticePhase.Idle,
                            countInBeat = null,
                            error = it.message ?: "无法开始跟弹",
                        ),
                    )
                }
        }
    }

    fun injectScreenNote(pitch: Int) = controller.injectScreenNote(pitch)

    suspend fun finishRecording(): StoredPracticeAttempt? {
        if (state.busy) return null
        if (!state.canFinishRecording) {
            publish(state.copy(error = "倒计时尚未结束；可取消本次或留在当前页面"))
            return null
        }
        val target = checkNotNull(state.target) { "当前没有可跟弹的选段" }
        val captureStartedAt = checkNotNull(state.startedAt) { "当前没有可完成的跟弹" }
        val inputSnapshot = state.capture
        timelineJob?.cancel()
        timelineJob = null
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
                publish(
                    state.copy(
                        startedAt = null,
                        busy = false,
                        result = saved,
                        recent = recent,
                        phase = MidiPracticePhase.Idle,
                        countInBeat = null,
                    ),
                )
                saved
            },
            onFailure = { failure ->
                publish(
                    state.copy(
                        startedAt = null,
                        busy = false,
                        error = failure.message ?: "无法保存跟弹结果",
                        phase = MidiPracticePhase.Idle,
                        countInBeat = null,
                    ),
                )
                null
            },
        )
    }

    fun cancelRecording() {
        timelineJob?.cancel()
        timelineJob = null
        controller.cancelRecording()
        performedNotes = emptyList()
        publish(
            state.copy(
                startedAt = null,
                error = null,
                result = null,
                phase = MidiPracticePhase.Idle,
                countInBeat = null,
                liveFeedback = null,
            ),
        )
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
        timelineJob?.cancel()
        scope.cancel()
        controller.close()
        onStateChanged = {}
    }

    private fun publish(next: WorkspaceMidiSessionState) {
        if (closed) return
        state = next
        onStateChanged(next)
    }

    private fun publishLiveFeedback() {
        val plan = state.target?.plan ?: return
        if (state.phase != MidiPracticePhase.Recording) return
        val elapsed = controller.recordingElapsedNanos() / 1_000_000L
        publish(
            state.copy(
                liveFeedback = IncrementalPerformanceMatcher.evaluate(plan, performedNotes, elapsed),
            ),
        )
    }
}
