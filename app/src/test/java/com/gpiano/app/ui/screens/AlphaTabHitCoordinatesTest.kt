package com.gpiano.app.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Test

class AlphaTabHitCoordinatesTest {
    @Test
    fun convertsViewportTouchIntoAlphaTabContentCoordinates() {
        assertEquals(150.0, AlphaTabHitCoordinates.toContent(150f, 50, 200, 2f), 0.0001)
        assertEquals(25.0, AlphaTabHitCoordinates.toContent(100f, 50, 0, 2f), 0.0001)
    }
}
