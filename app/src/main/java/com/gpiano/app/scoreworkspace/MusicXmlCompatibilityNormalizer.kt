package com.gpiano.app.scoreworkspace

import java.io.StringWriter
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult
import org.w3c.dom.Document
import org.w3c.dom.Element

/**
 * Keeps valid MusicXML semantics while normalizing producer-specific ordering
 * that is known to create cyclic ties in alphaTab's MusicXML importer.
 */
object MusicXmlCompatibilityNormalizer {
    fun normalize(xml: String): String {
        val document = MusicXmlScoreParser.parseDocument(xml)
        var changed = false
        val notes = document.getElementsByTagName("note")
        for (index in 0 until notes.length) {
            val note = notes.item(index) as? Element ?: continue
            changed = reorderStopBeforeStart(note, "tie") || changed
            note.firstDirectChild("notations")?.let { notations ->
                changed = reorderStopBeforeStart(notations, "tied") || changed
            }
        }
        return if (changed) document.serializeMusicXml() else xml
    }

    private fun reorderStopBeforeStart(parent: Element, tagName: String): Boolean {
        val ties = parent.directChildren(tagName)
        if (ties.size < 2) return false
        val firstStart = ties.indexOfFirst { it.getAttribute("type") == "start" }
        val laterStops = ties.drop(firstStart.coerceAtLeast(0) + 1)
            .filter { it.getAttribute("type") == "stop" }
        if (firstStart < 0 || laterStops.isEmpty()) return false

        val anchor = ties[firstStart]
        laterStops.forEach { parent.insertBefore(it, anchor) }
        return true
    }

    private fun Document.serializeMusicXml(): String {
        val transformer = TransformerFactory.newInstance().newTransformer().apply {
            setOutputProperty(OutputKeys.ENCODING, "UTF-8")
            setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "no")
            setOutputProperty(OutputKeys.INDENT, "yes")
            doctype?.publicId?.let { setOutputProperty(OutputKeys.DOCTYPE_PUBLIC, it) }
            doctype?.systemId?.let { setOutputProperty(OutputKeys.DOCTYPE_SYSTEM, it) }
            runCatching { setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "2") }
        }
        return StringWriter().also { writer ->
            transformer.transform(DOMSource(this), StreamResult(writer))
        }.toString()
    }
}
