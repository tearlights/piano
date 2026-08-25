package com.gpiano.app.ui.screens

internal data class WorkspaceMeasureSelection(
    val selectionStartMeasure: Int,
    val selectionEndMeasure: Int,
    val focusedMeasure: Int,
) {
    init {
        require(selectionStartMeasure >= 1)
        require(selectionEndMeasure >= selectionStartMeasure)
        require(focusedMeasure >= 1)
    }

    companion object {
        fun normalizeRange(start: Int, end: Int, measureCount: Int): IntRange {
            require(measureCount >= 1)
            val normalizedStart = start.coerceIn(1, measureCount)
            val normalizedEnd = end.coerceIn(normalizedStart, measureCount)
            return normalizedStart..normalizedEnd
        }
    }

    fun afterScoreTap(measure: Int, measureCount: Int): WorkspaceMeasureSelection {
        require(measureCount >= 1)
        val tapped = measure.coerceIn(1, measureCount)
        return copy(
            selectionStartMeasure = tapped,
            selectionEndMeasure = selectionEndMeasure.coerceAtLeast(tapped),
            focusedMeasure = tapped,
        )
    }
}
