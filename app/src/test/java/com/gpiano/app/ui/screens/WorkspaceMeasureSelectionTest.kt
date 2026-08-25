package com.gpiano.app.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Test

class WorkspaceMeasureSelectionTest {
    @Test
    fun normalizesLongScoreRangeWithoutCrossingEndpoints() {
        assertEquals(1..240, WorkspaceMeasureSelection.normalizeRange(-5, 999, 240))
        assertEquals(180..180, WorkspaceMeasureSelection.normalizeRange(180, 20, 240))
    }

    @Test
    fun preservesPreciseInteriorRange() {
        assertEquals(117..163, WorkspaceMeasureSelection.normalizeRange(117, 163, 240))
    }

    @Test
    fun scoreTapMovesStartAndFocusWhileKeepingOrCollapsingEnd() {
        val original = WorkspaceMeasureSelection(3, 8, 5)

        assertEquals(WorkspaceMeasureSelection(6, 8, 6), original.afterScoreTap(6, 24))
        assertEquals(WorkspaceMeasureSelection(12, 12, 12), original.afterScoreTap(12, 24))
    }
}
