package com.gpiano.app.midi

import org.json.JSONArray
import org.json.JSONObject

object PerformanceReportJson {
    fun encode(report: PerformanceReport): String = JSONObject()
        .put("schemaVersion", 1)
        .put("expectedCount", report.expectedCount)
        .put("playedCount", report.playedCount)
        .put("pitchMatchedCount", report.pitchMatchedCount)
        .put("rhythmMatchedCount", report.rhythmMatchedCount)
        .put("missedCount", report.missedCount)
        .put("extraCount", report.extraCount)
        .put("wrongPitchCount", report.wrongPitchCount)
        .put("continuityPercent", report.continuityPercent)
        .put("pitchAccuracyPercent", report.pitchAccuracyPercent)
        .put("rhythmAccuracyPercent", report.rhythmAccuracyPercent)
        .put("associationToleranceMillis", report.associationToleranceMillis)
        .put("rhythmToleranceMillis", report.rhythmToleranceMillis)
        .put(
            "matches",
            JSONArray().apply {
                report.matches.forEach { match ->
                    put(
                        JSONObject()
                            .put("kind", match.kind.name)
                            .putNullable("measureIndex", match.measureIndex)
                            .putNullable("targetEventId", match.targetEventId)
                            .putNullable("expectedPitch", match.expectedPitch)
                            .putNullable("actualSequence", match.actualSequence)
                            .putNullable("actualPitch", match.actualPitch)
                            .putNullable("timingErrorMillis", match.timingErrorMillis),
                    )
                }
            },
        )
        .toString()

    fun decode(json: String): PerformanceReport {
        val root = JSONObject(json)
        require(root.getInt("schemaVersion") == 1) { "跟弹结果版本不受支持" }
        val matches = root.getJSONArray("matches").let { array ->
            List(array.length()) { index ->
                val item = array.getJSONObject(index)
                PerformanceMatch(
                    kind = MatchKind.valueOf(item.getString("kind")),
                    measureIndex = item.nullableInt("measureIndex"),
                    targetEventId = item.nullableString("targetEventId"),
                    expectedPitch = item.nullableInt("expectedPitch"),
                    actualSequence = item.nullableInt("actualSequence"),
                    actualPitch = item.nullableInt("actualPitch"),
                    timingErrorMillis = item.nullableLong("timingErrorMillis"),
                )
            }
        }
        return PerformanceReport(
            expectedCount = root.getInt("expectedCount"),
            playedCount = root.getInt("playedCount"),
            pitchMatchedCount = root.getInt("pitchMatchedCount"),
            rhythmMatchedCount = root.getInt("rhythmMatchedCount"),
            missedCount = root.getInt("missedCount"),
            extraCount = root.getInt("extraCount"),
            wrongPitchCount = root.getInt("wrongPitchCount"),
            continuityPercent = root.getInt("continuityPercent"),
            pitchAccuracyPercent = root.getInt("pitchAccuracyPercent"),
            rhythmAccuracyPercent = root.getInt("rhythmAccuracyPercent"),
            associationToleranceMillis = root.getLong("associationToleranceMillis"),
            rhythmToleranceMillis = root.getLong("rhythmToleranceMillis"),
            matches = matches,
        ).also(::requireValid)
    }

    fun requireValid(report: PerformanceReport) {
        val counts = listOf(
            report.expectedCount,
            report.playedCount,
            report.pitchMatchedCount,
            report.rhythmMatchedCount,
            report.missedCount,
            report.extraCount,
            report.wrongPitchCount,
        )
        require(counts.all { it >= 0 }) { "跟弹结果计数无效" }
        require(
            listOf(report.continuityPercent, report.pitchAccuracyPercent, report.rhythmAccuracyPercent)
                .all { it in 0..100 },
        ) { "跟弹结果百分比无效" }
        require(report.associationToleranceMillis > 0 && report.rhythmToleranceMillis > 0) {
            "跟弹匹配容差无效"
        }
        require(report.pitchMatchedCount <= report.expectedCount) { "音高匹配数超出目标音符数" }
        require(report.rhythmMatchedCount <= report.pitchMatchedCount) { "节奏匹配数超出音高匹配数" }
        require(report.missedCount + report.wrongPitchCount + report.pitchMatchedCount == report.expectedCount) {
            "目标音符统计不一致"
        }
        require(report.pitchMatchedCount + report.wrongPitchCount + report.extraCount == report.playedCount) {
            "实际按键统计不一致"
        }
        require(report.matches.count { it.targetEventId != null } == report.expectedCount) { "目标匹配明细不完整" }
        require(report.matches.count { it.kind == MatchKind.Extra } == report.extraCount) { "多音明细不一致" }
        report.matches.forEach { match ->
            require(match.measureIndex == null || match.measureIndex >= 1) { "匹配小节无效" }
            require(match.expectedPitch == null || match.expectedPitch in 0..127) { "目标音高无效" }
            require(match.actualPitch == null || match.actualPitch in 0..127) { "实际音高无效" }
            require(match.actualSequence == null || match.actualSequence >= 0) { "按键序号无效" }
        }
    }
}

private fun JSONObject.putNullable(key: String, value: Any?): JSONObject = put(key, value ?: JSONObject.NULL)
private fun JSONObject.nullableString(key: String): String? = if (!has(key) || isNull(key)) null else getString(key)
private fun JSONObject.nullableInt(key: String): Int? = if (!has(key) || isNull(key)) null else getInt(key)
private fun JSONObject.nullableLong(key: String): Long? = if (!has(key) || isNull(key)) null else getLong(key)
