package com.gpiano.app.ui.screens

internal object AlphaTabHitCoordinates {
    fun isInsideViewport(
        rawX: Float,
        rawY: Float,
        left: Int,
        top: Int,
        width: Int,
        height: Int,
    ): Boolean = rawX >= left && rawX <= left + width && rawY >= top && rawY <= top + height

    fun toContent(rawPixels: Float, viewportOriginPixels: Int, scrollPixels: Int, density: Float): Double {
        require(density > 0f)
        return (rawPixels - viewportOriginPixels + scrollPixels) / density.toDouble()
    }
}
