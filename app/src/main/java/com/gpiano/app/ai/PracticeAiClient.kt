package com.gpiano.app.ai

import com.gpiano.app.scoreworkspace.PlaybackHand
import com.gpiano.app.scoreworkspace.PlaybackSelection
import com.gpiano.app.scoreworkspace.PracticeAnalysis
import com.gpiano.app.scoreworkspace.PracticeVersionPreset
import com.gpiano.app.scoreworkspace.RecommendedPlayback
import com.gpiano.app.scoreworkspace.ScoreEventIr
import com.gpiano.app.scoreworkspace.ScoreHand
import com.gpiano.app.scoreworkspace.ScoreIr
import java.net.HttpURLConnection
import java.net.URI
import org.json.JSONArray
import org.json.JSONObject

data class PracticeAiEvidence(
    val measureIndexes: List<Int>,
    val eventIds: List<String>,
    val summary: String,
)

data class PracticeAiResult(
    val answer: String,
    val practiceSteps: List<String>,
    val evidence: List<PracticeAiEvidence>,
    val suggestedPlayback: RecommendedPlayback?,
    val practiceVersionPreset: PracticeVersionPreset?,
)

class PracticeAiServiceException(message: String, val code: String) : Exception(message)

class PracticeAiClient {
    fun health(settings: PracticeAiSettings): String =
        readJson(open(settings, "/health", "GET")).getString("status")

    fun analyze(
        settings: PracticeAiSettings,
        question: String,
        score: ScoreIr,
        analysis: PracticeAnalysis,
        selection: PlaybackSelection,
    ): PracticeAiResult {
        val request = buildRequest(question, score, analysis, selection)
        val connection = open(settings, "/v1/analyze", "POST").apply {
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            val bytes = request.toString().toByteArray(Charsets.UTF_8)
            require(bytes.size <= MAX_REQUEST_BYTES) { "当前选段过大，请缩短后再提问" }
            setFixedLengthStreamingMode(bytes.size)
            doOutput = true
            outputStream.use { it.write(bytes) }
        }
        return parseResult(readJson(connection), request)
    }

    internal fun buildRequest(
        question: String,
        score: ScoreIr,
        analysis: PracticeAnalysis,
        selection: PlaybackSelection,
    ): JSONObject {
        val normalizedQuestion = question.trim()
        require(normalizedQuestion.isNotEmpty() && normalizedQuestion.length <= 800) { "问题必须为 1～800 个字符" }
        require(selection.startMeasure == analysis.fromMeasure && selection.endMeasure == analysis.toMeasure) {
            "本地分析范围与提问范围不一致"
        }
        val events = score.events.asSequence()
            .filter { it.measureIndex in selection.startMeasure..selection.endMeasure }
            .filter { event -> handMatches(event.hand, selection.hand) }
            .toList()
        require(events.isNotEmpty()) { "当前手别在这个选段中没有可分析的事件" }
        require(events.size <= 500) { "当前选段事件过多，请缩短后再提问" }
        val allowedIds = events.mapTo(hashSetOf(), ScoreEventIr::id)
        return JSONObject()
            .put("schemaVersion", 1)
            .put("consent", true)
            .put("question", normalizedQuestion)
            .put(
                "selection",
                JSONObject()
                    .put("fromMeasure", selection.startMeasure)
                    .put("toMeasure", selection.endMeasure)
                    .put("hand", selection.hand.name)
                    .put("speed", selection.speed),
            )
            .put(
                "score",
                JSONObject()
                    .put("title", score.title.take(200))
                    .putNullable("tempoBpm", score.tempoBpm)
                    .putNullable("fifths", score.fifths)
                    .putNullable("beats", score.beats)
                    .putNullable("beatType", score.beatType)
                    .put("events", JSONArray().apply { events.forEach { put(it.toAiJson(score)) } }),
            )
            .put(
                "localAnalysis",
                JSONObject()
                    .put("engineId", analysis.engineId)
                    .put("overview", analysis.overview)
                    .put(
                        "guidance",
                        JSONArray().apply {
                            analysis.guidance.forEach { guidance ->
                                put(
                                    JSONObject()
                                        .put("topic", guidance.topic.name)
                                        .put("title", guidance.title)
                                        .put("explanation", guidance.explanation)
                                        .put("evidenceSummary", guidance.evidence.description)
                                        .put(
                                            "measureIndexes",
                                            JSONArray(
                                                guidance.evidence.measureIndexes.filter {
                                                    it in selection.startMeasure..selection.endMeasure
                                                },
                                            ),
                                        )
                                        .put(
                                            "eventIds",
                                            JSONArray(guidance.evidence.eventIds.filter(allowedIds::contains)),
                                        )
                                        .put(
                                            "practiceSteps",
                                            JSONArray(guidance.instructions.map { it.text }),
                                        ),
                                )
                            }
                        },
                    ),
            )
    }

    private fun parseResult(root: JSONObject, request: JSONObject): PracticeAiResult {
        require(root.getInt("schemaVersion") == 1) { "AI 结果版本不受支持" }
        val answer = root.getString("answer").trim()
        require(answer.isNotEmpty() && answer.length <= 4_000) { "AI 回答为空或过长" }
        val steps = root.getJSONArray("practiceSteps").strings()
        require(steps.size in 1..6 && steps.all { it.isNotBlank() && it.length <= 500 }) { "AI 练习步骤无效" }
        val selection = request.getJSONObject("selection")
        val allowedMeasures = selection.getInt("fromMeasure")..selection.getInt("toMeasure")
        val allowedEvents = request.getJSONObject("score").getJSONArray("events").objects()
            .mapTo(hashSetOf()) { it.getString("id") }
        val evidence = root.optJSONArray("evidence")?.objects().orEmpty().map { item ->
            PracticeAiEvidence(
                measureIndexes = item.getJSONArray("measureIndexes").ints().also { values ->
                    require(values.isNotEmpty() && values.all { it in allowedMeasures }) { "AI 引用了选段外的小节" }
                },
                eventIds = item.getJSONArray("eventIds").strings().also { values ->
                    require(values.isNotEmpty() && values.all(allowedEvents::contains)) { "AI 引用了未发送的谱面事件" }
                },
                summary = item.getString("summary").trim().also {
                    require(it.isNotEmpty() && it.length <= 500) { "AI 谱面依据无效" }
                },
            )
        }.also { require(it.size in 1..12) { "AI 谱面依据数量无效" } }
        val playback = root.optJSONObject("suggestedPlayback")?.let { item ->
            RecommendedPlayback(
                speed = item.getDouble("speed").also { require(it in 0.25..1.5) { "AI 建议速度无效" } },
                hand = runCatching { PlaybackHand.valueOf(item.getString("hand")) }
                    .getOrElse { error("AI 建议手别无效") },
                looping = item.getBoolean("looping"),
            )
        }
        val preset = root.nullableString("practiceVersionPreset")?.let(PracticeVersionPreset::fromCode)
        return PracticeAiResult(answer, steps, evidence, playback, preset)
    }

    private fun open(settings: PracticeAiSettings, path: String, method: String): HttpURLConnection {
        val url = URI(settings.endpoint).resolve(path).toURL()
        return (url.openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            useCaches = false
            setRequestProperty("Authorization", "Bearer ${settings.token}")
            setRequestProperty("Accept", "application/json")
        }
    }

    internal fun readJson(connection: HttpURLConnection): JSONObject {
        val status = connection.responseCode
        val stream = if (status in 200..299) connection.inputStream else connection.errorStream
        val body = try {
            stream?.bufferedReader(Charsets.UTF_8)?.use { it.readTextLimited(MAX_RESPONSE_CHARS) }.orEmpty()
        } catch (_: ResponseTooLargeException) {
            throw PracticeAiServiceException("AI 服务响应过大", "response_too_large")
        }
        if (status !in 200..299) {
            val error = runCatching { JSONObject(body) }.getOrNull()
            throw PracticeAiServiceException(
                error?.optString("message")?.takeIf(String::isNotBlank) ?: "AI 服务返回错误（$status）",
                error?.optString("code")?.takeIf(String::isNotBlank) ?: "http_$status",
            )
        }
        return JSONObject(body)
    }

    private fun ScoreEventIr.toAiJson(score: ScoreIr): JSONObject {
        val divisions = score.parts.getOrNull(partIndex)?.measures?.getOrNull(measureIndex - 1)?.divisions ?: 1
        return JSONObject()
            .put("id", id)
            .put("measure", measureIndex)
            .put("hand", hand.name)
            .put("voice", voice)
            .putNullable("staff", staff)
            .put("onsetDivisions", onsetDivisions)
            .put("durationDivisions", durationDivisions)
            .put("divisionsPerQuarter", divisions)
            .put("rest", isRest)
            .putNullable("pitch", pitch?.displayName)
            .putNullable("midiPitch", pitch?.midi)
            .put("chordTone", isChordTone)
            .putNullable("noteType", noteType)
            .put("dots", dots)
            .putNullable("tupletActual", tupletActualNotes)
            .putNullable("tupletNormal", tupletNormalNotes)
            .put("tieStart", tieStart)
            .put("tieStop", tieStop)
            .put("slurStart", slurStart)
            .put("slurStop", slurStop)
    }

    private fun handMatches(hand: ScoreHand, selection: PlaybackHand): Boolean = when (selection) {
        PlaybackHand.Both -> true
        PlaybackHand.Right -> hand == ScoreHand.Right
        PlaybackHand.Left -> hand == ScoreHand.Left
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 20_000
        const val READ_TIMEOUT_MS = 100_000
        const val MAX_REQUEST_BYTES = 512 * 1024
        const val MAX_RESPONSE_CHARS = 512 * 1024
    }
}

private class ResponseTooLargeException : Exception()

private fun java.io.Reader.readTextLimited(limit: Int): String {
    val output = StringBuilder()
    val buffer = CharArray(2_048)
    while (true) {
        val read = read(buffer)
        if (read < 0) break
        if (output.length + read > limit) throw ResponseTooLargeException()
        output.append(buffer, 0, read)
    }
    return output.toString()
}

private fun JSONObject.putNullable(key: String, value: Any?): JSONObject = put(key, value ?: JSONObject.NULL)
private fun JSONObject.nullableString(key: String): String? = if (!has(key) || isNull(key)) null else getString(key)
private fun JSONArray.strings(): List<String> = List(length()) { getString(it) }
private fun JSONArray.ints(): List<Int> = List(length()) { getInt(it) }
private fun JSONArray.objects(): List<JSONObject> = List(length()) { getJSONObject(it) }
