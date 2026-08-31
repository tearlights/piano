@file:OptIn(kotlin.ExperimentalUnsignedTypes::class, kotlin.contracts.ExperimentalContracts::class)

package com.gpiano.app.ui.screens

import alphaTab.PlayerSettings
import alphaTab.ScrollMode
import alphaTab.model.Score
import com.gpiano.app.scoreworkspace.PlaybackSelection

internal data class AlphaTabMeasureTiming(
    val measureIndex: Int,
    val startTick: Double,
    val endTick: Double,
)

internal data class AlphaTabPlaybackRange(
    val startTick: Double,
    val endTick: Double,
)

/** Resolves audible playback bounds exclusively from alphaTab's loaded score timeline. */
internal object AlphaTabPlaybackTimeline {
    /**
     * MusicXML permits importers to expose beats beyond a nominal bar boundary. alphaTab keeps
     * those beats but normally advances the next master bar by the time signature duration,
     * causing the two bars to sound at the same time. Treat only overfull bars as free-length
     * bars in alphaTab's transient model, then rebuild their starts from the audible durations.
     */
    fun normalizeOverfullMeasures(score: Score): Int {
        val overfullBars = mutableListOf<alphaTab.model.MasterBar>()
        score.masterBars.forEach { masterBar ->
            val actualDuration = score.tracks.maxOfOrNull { track ->
                track.staves.maxOfOrNull { staff ->
                    staff.bars.get(masterBar.index.toInt()).calculateDuration()
                } ?: 0.0
            } ?: 0.0
            if (actualDuration > masterBar.calculateDuration(false) + TICK_EPSILON) {
                overfullBars += masterBar
            }
        }
        if (overfullBars.isEmpty()) return 0

        overfullBars.forEach { it.isAnacrusis = true }
        var start = 0.0
        score.masterBars.forEach { masterBar ->
            masterBar.start = start
            start += masterBar.calculateDuration()
        }
        return overfullBars.size
    }

    fun resolve(
        selection: PlaybackSelection,
        measures: List<AlphaTabMeasureTiming>,
    ): AlphaTabPlaybackRange {
        require(measures.isNotEmpty()) { "播放器尚未建立小节时间轴" }
        val byIndex = measures.associateBy(AlphaTabMeasureTiming::measureIndex)
        require(byIndex.size == measures.size) { "播放器小节时间轴包含重复索引" }

        val first = byIndex[selection.startMeasure]
            ?: throw IllegalArgumentException("播放起始小节超出播放器乐谱范围")
        val last = byIndex[selection.endMeasure]
            ?: throw IllegalArgumentException("播放结束小节超出播放器乐谱范围")

        require(first.startTick.isFinite() && last.endTick.isFinite()) {
            "播放器小节时间轴包含无效位置"
        }
        require(first.startTick >= 0.0 && last.endTick > first.startTick) {
            "播放器小节时间轴范围无效"
        }
        return AlphaTabPlaybackRange(first.startTick, last.endTick)
    }

    private const val TICK_EPSILON = 0.001
}

/**
 * Disables alphaTab Android's internal scrolling animation. Gpiano follows measure changes
 * through Android scroll views because alphaTab 1.8.3 can produce a negative X duration.
 */
internal fun PlayerSettings.configureGpianoScrolling() {
    scrollMode = ScrollMode.Off
    // alphaTab's animated Android cursor can calculate a negative transition
    // duration when a MusicXML playback range crosses rendered systems. Keep
    // the native beat cursor/highlighting, but move it discretely from the
    // alphaTab clock instead of using the unsafe interpolation path.
    enableAnimatedBeatCursor = false
}
