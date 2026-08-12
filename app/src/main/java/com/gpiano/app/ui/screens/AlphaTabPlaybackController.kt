package com.gpiano.app.ui.screens

import android.util.Log
import alphaTab.AlphaTabView
import alphaTab.collections.List as AlphaTabList
import alphaTab.model.Track
import alphaTab.synth.PlaybackRange
import alphaTab.synth.PlayerState
import com.gpiano.app.scoreworkspace.PlaybackHand
import com.gpiano.app.scoreworkspace.PlaybackPlan
import com.gpiano.app.scoreworkspace.ScoreHand
import kotlin.contracts.ExperimentalContracts

enum class ScorePlayerPhase {
    Preparing,
    Ready,
    Playing,
    Paused,
    Failed,
}

data class ScorePlayerUiState(
    val phase: ScorePlayerPhase = ScorePlayerPhase.Preparing,
    val currentMeasure: Int? = null,
    val error: String? = null,
) {
    val canStart: Boolean get() = phase == ScorePlayerPhase.Ready || phase == ScorePlayerPhase.Paused
}

@OptIn(ExperimentalContracts::class, ExperimentalUnsignedTypes::class)
class AlphaTabPlaybackController {
    var onStateChanged: (ScorePlayerUiState) -> Unit = {}

    private var view: AlphaTabView? = null
    private var handByTrack: Map<Int, ScoreHand> = emptyMap()
    private var state = ScorePlayerUiState()
    private var activePlan: PlaybackPlan? = null
    private val unsubscribe = mutableListOf<() -> Unit>()

    fun attach(view: AlphaTabView, handByTrack: Map<Int, ScoreHand>) {
        if (this.view === view) return
        detach()
        this.view = view
        this.handByTrack = handByTrack
        publish(ScorePlayerUiState(ScorePlayerPhase.Preparing))
        val api = view.api
        unsubscribe += api.playerReady.on {
            view.post { publishReadyIfPossible() }
        }
        unsubscribe += api.midiLoaded.on {
            view.post { publishReadyIfPossible() }
        }
        unsubscribe += api.playerStateChanged.on { event ->
            view.post {
                val phase = when (event.state) {
                    PlayerState.Playing -> ScorePlayerPhase.Playing
                    PlayerState.Paused -> if (event.stopped) ScorePlayerPhase.Ready else ScorePlayerPhase.Paused
                }
                Log.d(TAG, "state=${event.state} stopped=${event.stopped} looping=${api.isLooping}")
                publish(state.copy(phase = phase, error = null))
            }
        }
        unsubscribe += api.playerFinished.on {
            view.post {
                // alphaTab emits playerFinished at a playback-range boundary even when it
                // immediately starts the range again. Treat that as a loop boundary, not as
                // an idle player, otherwise the UI offers a second "play" action while audio
                // is still running.
                if (api.isLooping && activePlan != null) {
                    Log.d(TAG, "loop boundary")
                    publish(
                        state.copy(
                            phase = ScorePlayerPhase.Playing,
                            currentMeasure = activePlan?.selection?.startMeasure,
                            error = null,
                        ),
                    )
                } else {
                    Log.d(TAG, "playback finished")
                    activePlan = null
                    publish(state.copy(phase = ScorePlayerPhase.Ready, currentMeasure = null))
                }
            }
        }
        unsubscribe += api.playedBeatChanged.on { beat ->
            val measure = beat.voice.bar.masterBar.index.toInt() + 1
            view.post { publish(state.copy(currentMeasure = measure)) }
        }
        unsubscribe += api.error.on { error ->
            view.post { fail(error.message ?: "播放器发生错误") }
        }
    }

    fun detach(expectedView: AlphaTabView? = null) {
        if (expectedView != null && view !== expectedView) return
        runCatching { view?.api?.stop() }
        unsubscribe.forEach { runCatching { it() } }
        unsubscribe.clear()
        view = null
        activePlan = null
        handByTrack = emptyMap()
        publish(ScorePlayerUiState(ScorePlayerPhase.Preparing))
    }

    fun start(plan: PlaybackPlan): Result<Unit> = runCatching {
        val currentView = view ?: error("谱面播放器尚未连接")
        val api = currentView.api
        check(api.isReadyForPlayback) { "播放器仍在准备音色和乐谱，请稍候" }
        applyHandFilter(currentView, plan.selection.hand)

        val bars = api.tickCache?.masterBars ?: error("播放器尚未建立小节时间轴")
        val first = bars.get(plan.selection.startMeasure - 1)
        val last = bars.get(plan.selection.endMeasure - 1)
        check(first.masterBar.index.toInt() + 1 == plan.selection.startMeasure) { "播放起始小节映射失败" }
        check(last.masterBar.index.toInt() + 1 == plan.selection.endMeasure) { "播放结束小节映射失败" }
        check(first.start.roundToLongSafe() == plan.rangeStartTick) {
            "播放时间轴与结构化乐谱不一致"
        }

        api.playbackRange = PlaybackRange().apply {
            startTick = first.start
            endTick = last.end
        }
        api.playbackSpeed = plan.selection.speed
        api.isLooping = plan.selection.looping
        api.tickPosition = first.start
        activePlan = plan
        Log.d(
            TAG,
            "start measures=${plan.selection.startMeasure}-${plan.selection.endMeasure} " +
                "ticks=${first.start}-${last.end} speed=${plan.selection.speed} " +
                "loop=${plan.selection.looping} hand=${plan.selection.hand}",
        )
        check(api.play()) { "播放器无法开始播放" }
    }.onFailure { error -> fail(error.message ?: "无法播放所选片段") }

    fun pause() {
        runCatching { view?.api?.pause() }
            .onFailure { fail(it.message ?: "无法暂停播放") }
    }

    fun resume(): Result<Unit> = runCatching {
        val api = view?.api ?: error("谱面播放器尚未连接")
        check(activePlan != null) { "没有可继续的播放片段" }
        check(api.isReadyForPlayback && api.play()) { "无法继续播放" }
    }.onFailure { fail(it.message ?: "无法继续播放") }

    fun stop() {
        runCatching { view?.api?.stop() }
            .onSuccess {
                activePlan = null
                publish(state.copy(phase = ScorePlayerPhase.Ready, currentMeasure = null, error = null))
            }
            .onFailure { fail(it.message ?: "无法停止播放") }
    }

    fun markScoreRendered() {
        publishReadyIfPossible()
    }

    private fun applyHandFilter(view: AlphaTabView, hand: PlaybackHand) {
        val tracks = view.api.tracks
        if (hand != PlaybackHand.Both) {
            check(handByTrack.values.any { it == ScoreHand.Right } && handByTrack.values.any { it == ScoreHand.Left }) {
                "当前乐谱无法可靠拆分左右手轨道"
            }
        }
        val muted = AlphaTabList<Track>()
        val audible = AlphaTabList<Track>()
        for (track in tracks) {
            val trackHand = handByTrack[track.index.toInt()] ?: ScoreHand.Unknown
            val shouldMute = when (hand) {
                PlaybackHand.Both -> false
                PlaybackHand.Right -> trackHand != ScoreHand.Right
                PlaybackHand.Left -> trackHand != ScoreHand.Left
            }
            if (shouldMute) muted.push(track) else audible.push(track)
        }
        if (audible.length > 0) view.api.changeTrackMute(audible, false)
        if (muted.length > 0) view.api.changeTrackMute(muted, true)
        Log.d(
            TAG,
            "hand=$hand audible=${audible.joinToString { it.index.toString() }} " +
                "muted=${muted.joinToString { it.index.toString() }}",
        )
    }

    private fun publishReadyIfPossible() {
        val ready = view?.api?.isReadyForPlayback == true
        if (ready && state.phase == ScorePlayerPhase.Preparing) {
            publish(state.copy(phase = ScorePlayerPhase.Ready, error = null))
        }
    }

    private fun fail(message: String) {
        publish(state.copy(phase = ScorePlayerPhase.Failed, error = message))
    }

    private fun publish(newState: ScorePlayerUiState) {
        state = newState
        onStateChanged(newState)
    }

    private companion object {
        const val TAG = "GpianoPlayer"
    }
}

private fun Double.roundToLongSafe(): Long = kotlin.math.round(this).toLong()
