package com.gpiano.app.scoreworkspace

data class MusicalDuration(
    val noteType: String,
    val dots: Int = 0,
    val actualNotes: Int? = null,
    val normalNotes: Int? = null,
) {
    init {
        require(noteType in DENOMINATORS) { "暂不支持的音符时值：$noteType" }
        require(dots in 0..2) { "附点数量超出当前支持范围" }
        require((actualNotes == null) == (normalNotes == null)) { "连音比例必须同时提供" }
        actualNotes?.let { require(it in 2..16) { "连音实际音符数无效" } }
        normalNotes?.let { require(it in 1..16) { "连音标准音符数无效" } }
    }

    fun toDivisions(quarterDivisions: Int): Long {
        require(quarterDivisions > 0) { "MusicXML divisions 无效" }
        val dotNumerator = (1L shl (dots + 1)) - 1L
        val dotDenominator = 1L shl dots
        val tupletNumerator = normalNotes?.toLong() ?: 1L
        val tupletDenominator = actualNotes?.toLong() ?: 1L
        val numerator = quarterDivisions.toLong() * 4L * dotNumerator * tupletNumerator
        val denominator = DENOMINATORS.getValue(noteType) * dotDenominator * tupletDenominator
        require(numerator % denominator == 0L) { "当前乐谱精度无法表示这个时值" }
        return numerator / denominator
    }

    val displayName: String
        get() = buildString {
            append(DISPLAY_NAMES.getValue(noteType))
            repeat(dots) { append("·") }
            if (actualNotes != null && normalNotes != null) append("（$actualNotes:$normalNotes）")
        }

    companion object {
        private val DENOMINATORS = mapOf(
            "maxima" to 0L,
            "long" to 0L,
            "breve" to 2L,
            "whole" to 1L,
            "half" to 2L,
            "quarter" to 4L,
            "eighth" to 8L,
            "16th" to 16L,
            "32nd" to 32L,
            "64th" to 64L,
            "128th" to 128L,
        ).filterValues { it > 0L }

        private val DISPLAY_NAMES = mapOf(
            "breve" to "二全音符",
            "whole" to "全音符",
            "half" to "二分音符",
            "quarter" to "四分音符",
            "eighth" to "八分音符",
            "16th" to "十六分音符",
            "32nd" to "三十二分音符",
            "64th" to "六十四分音符",
            "128th" to "一百二十八分音符",
        )

        val commonValues = listOf("whole", "half", "quarter", "eighth", "16th")
            .map(::MusicalDuration)

        fun from(event: ScoreEventIr): MusicalDuration? {
            val type = event.noteType ?: return null
            return runCatching {
                MusicalDuration(type, event.dots, event.tupletActualNotes, event.tupletNormalNotes)
            }.getOrNull()
        }
    }
}
