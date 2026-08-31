package com.gpiano.app.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Test

class AlphaTabHitCoordinatesTest {
    @Test
    fun convertsViewportTouchIntoAlphaTabContentCoordinates() {
        assertEquals(150.0, AlphaTabHitCoordinates.toContent(150f, 50, 200, 2f), 0.0001)
        assertEquals(25.0, AlphaTabHitCoordinates.toContent(100f, 50, 0, 2f), 0.0001)
    }

    @Test
    fun rejectsBottomNavigationOutsideScoreViewport() {
        assertEquals(true, AlphaTabHitCoordinates.isInsideViewport(800f, 1_000f, 0, 494, 1_600, 1_698))
        assertEquals(false, AlphaTabHitCoordinates.isInsideViewport(1_120f, 2_380f, 0, 494, 1_600, 1_698))
    }
}
