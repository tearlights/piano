package com.gpiano.app.scoreworkspace

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory

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
        MusicXmlDocument(
            sourceName = assetName,
            xml = xml,
            summary = MusicXmlInspector.inspect(xml),
        )
    }
}

object MusicXmlInspector {
    fun inspect(xml: String): MusicXmlSummary {
        val parser = XmlPullParserFactory.newInstance().newPullParser().apply {
            setInput(xml.reader())
        }
        val parts = linkedMapOf<String, PartAccumulator>()
        var currentPartId: String? = null
        var pendingPartId: String? = null
        var capture: String? = null
        var title = "未命名练习谱"
        var fifths: Int? = null
        var beats: Int? = null
        var beatType: Int? = null

        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "score-part" -> pendingPartId = parser.getAttributeValue(null, "id")
                    "part-name" -> capture = "part-name"
                    "movement-title" -> capture = "title"
                    "fifths" -> capture = "fifths"
                    "beats" -> capture = "beats"
                    "beat-type" -> capture = "beat-type"
                    "part" -> {
                        currentPartId = parser.getAttributeValue(null, "id")
                        currentPartId?.let { parts.getOrPut(it) { PartAccumulator(it) } }
                    }
                    "measure" -> currentPartId?.let { parts.getOrPut(it) { PartAccumulator(it) }.measureCount += 1 }
                }

                XmlPullParser.TEXT -> when (capture) {
                    "title" -> title = parser.text.trim().ifBlank { title }
                    "part-name" -> pendingPartId?.let { id -> parts.getOrPut(id) { PartAccumulator(id) }.name = parser.text.trim() }
                    "fifths" -> fifths = parser.text.trim().toIntOrNull() ?: fifths
                    "beats" -> beats = parser.text.trim().toIntOrNull() ?: beats
                    "beat-type" -> beatType = parser.text.trim().toIntOrNull() ?: beatType
                }

                XmlPullParser.END_TAG -> when (parser.name) {
                    "part" -> currentPartId = null
                    "score-part" -> pendingPartId = null
                    "movement-title", "part-name", "fifths", "beats", "beat-type" -> capture = null
                }
            }
            parser.next()
        }

        val parsedParts = parts.values.map { MusicXmlPart(it.id, it.name, it.measureCount) }
        return MusicXmlSummary(
            title = title,
            parts = parsedParts,
            measureCount = parsedParts.maxOfOrNull { it.measureCount } ?: 0,
            fifths = fifths,
            beats = beats,
            beatType = beatType,
        )
    }

    private data class PartAccumulator(
        val id: String,
        var name: String? = null,
        var measureCount: Int = 0,
    )
}

const val DEMO_MUSIC_XML = "1785910774247300_654.musicxml"
