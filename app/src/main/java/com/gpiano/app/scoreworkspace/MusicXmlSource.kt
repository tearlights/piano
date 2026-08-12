package com.gpiano.app.scoreworkspace

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Provides MusicXML without coupling the workspace to a particular source.
 * The debug implementation reads the supplied test score; an OMR adapter will
 * implement the same interface later.
 */
interface MusicXmlSource {
    suspend fun load(): MusicXmlDocument
}

data class MusicXmlDocument(
    val sourceName: String,
    val xml: String,
    val score: ScoreIr,
    val summary: MusicXmlSummary,
)

data class MusicXmlSummary(
    val title: String,
    val parts: List<MusicXmlPart>,
    val measureCount: Int,
    val fifths: Int?,
    val beats: Int?,
    val beatType: Int?,
)

data class MusicXmlPart(
    val id: String,
    val name: String?,
    val measureCount: Int,
)

class AssetMusicXmlSource(
    private val context: Context,
    private val assetName: String = DEMO_MUSIC_XML,
) : MusicXmlSource {
    override suspend fun load(): MusicXmlDocument = withContext(Dispatchers.IO) {
        val xml = context.assets.open(assetName).bufferedReader().use { it.readText() }
        val score = MusicXmlScoreParser.parse(xml)
        MusicXmlDocument(
            sourceName = assetName,
            xml = xml,
            score = score,
            summary = score.toSummary(),
        )
    }
}

object MusicXmlInspector {
    fun inspect(xml: String): MusicXmlSummary = MusicXmlScoreParser.parse(xml).toSummary()
}

fun ScoreIr.toSummary(): MusicXmlSummary = MusicXmlSummary(
    title = title,
    parts = parts.map { part ->
        MusicXmlPart(
            id = part.id,
            name = part.name,
            measureCount = part.measures.size,
        )
    },
    measureCount = measureCount,
    fifths = fifths,
    beats = beats,
    beatType = beatType,
)

const val DEMO_MUSIC_XML = "1785910774247300_654.musicxml"
