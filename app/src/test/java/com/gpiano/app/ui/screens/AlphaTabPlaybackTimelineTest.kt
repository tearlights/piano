@file:OptIn(kotlin.ExperimentalUnsignedTypes::class, kotlin.contracts.ExperimentalContracts::class)

package com.gpiano.app.ui.screens

import alphaTab.PlayerSettings
import alphaTab.ScrollMode
import com.gpiano.app.scoreworkspace.PlaybackSelection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class AlphaTabPlaybackTimelineTest {
    @Test
    fun `disables alphaTab scrolling to avoid negative Android animation durations`() {
        val settings = PlayerSettings()

        settings.configureGpianoScrolling()

        assertEquals(ScrollMode.Off, settings.scrollMode)
        assertEquals(false, settings.enableAnimatedBeatCursor)
    }

    @Test
    fun `uses alphaTab bounds when structural timeline has diverged`() {
        val range = AlphaTabPlaybackTimeline.resolve(
            selection = PlaybackSelection(startMeasure = 6, endMeasure = 7),
            measures = (1..8).map { measure ->
                AlphaTabMeasureTiming(
                    measureIndex = measure,
                    startTick = (measure - 1) * 3_840.0,
                    endTick = measure * 3_840.0,
                )
            },
        )

        // The imported regression score's ScoreIR plan starts measure 6 at 20,640,
        // while alphaTab starts it at 19,200. Audible playback must follow alphaTab.
        assertEquals(19_200.0, range.startTick, 0.0)
        assertEquals(26_880.0, range.endTick, 0.0)
    }

    @Test
    fun `rejects a selected measure outside alphaTab timeline`() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            AlphaTabPlaybackTimeline.resolve(
                selection = PlaybackSelection(startMeasure = 2, endMeasure = 3),
                measures = listOf(AlphaTabMeasureTiming(1, 0.0, 3_840.0)),
            )
        }

        assertEquals("播放起始小节超出播放器乐谱范围", error.message)
    }

    @Test
    fun `rejects duplicate alphaTab measure indexes`() {
        assertThrows(IllegalArgumentException::class.java) {
            AlphaTabPlaybackTimeline.resolve(
                selection = PlaybackSelection(startMeasure = 1, endMeasure = 1),
                measures = listOf(
                    AlphaTabMeasureTiming(1, 0.0, 3_840.0),
                    AlphaTabMeasureTiming(1, 3_840.0, 7_680.0),
                ),
            )
        }
    }
}
