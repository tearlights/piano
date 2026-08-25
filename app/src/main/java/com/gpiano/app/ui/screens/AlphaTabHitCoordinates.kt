package com.gpiano.app.ui.screens

internal object AlphaTabHitCoordinates {
    fun toContent(rawPixels: Float, viewportOriginPixels: Int, scrollPixels: Int, density: Float): Double {
        require(density > 0f)
        return (rawPixels - viewportOriginPixels + scrollPixels) / density.toDouble()
    }
}
