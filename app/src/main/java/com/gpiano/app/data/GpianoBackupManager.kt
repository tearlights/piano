package com.gpiano.app.data

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class GpianoBackupManager(private val context: Context) {
    fun export(scores: List<Score>, destination: Uri) {
        context.contentResolver.openOutputStream(destination)?.use { output ->
            ZipOutputStream(output).use { zip ->
                val items = JSONArray()
                scores.forEach { score ->
                    items.put(JSONObject().apply {
                        put("id", score.id); put("title", score.title); put("fileName", score.fileName)
                        put("relativePath", score.relativePath); put("mimeType", score.mimeType)
                        put("importedAt", score.importedAt); put("isFavorite", score.isFavorite)
                    })
                    val source = File(context.filesDir, score.relativePath)
                    if (source.exists()) {
                        zip.putNextEntry(ZipEntry(score.relativePath))
                        source.inputStream().use { it.copyTo(zip) }
                        zip.closeEntry()
                    }
                }
                zip.putNextEntry(ZipEntry("library.json"))
                zip.write(JSONObject().put("formatVersion", 1).put("scores", items).toString().toByteArray())
                zip.closeEntry()
            }
        } ?: error("无法写入备份文件")
    }

    fun restore(source: Uri): List<Score> {
        val bytes = context.contentResolver.openInputStream(source)?.use { input -> ZipInputStream(input).use { zip ->
            var library: ByteArray? = null; var entry = zip.nextEntry
            while (entry != null) {
                if (entry.name == "library.json") library = zip.readBytes()
                else if (entry.name.startsWith("scores/") && !entry.name.contains("..")) { val target = File(context.filesDir, entry.name); target.parentFile?.mkdirs(); target.outputStream().use { zip.copyTo(it) } }
                entry = zip.nextEntry
            }; library ?: error("备份包缺少 library.json")
        } } ?: error("无法读取备份文件")
        val items = JSONObject(String(bytes)).getJSONArray("scores")
        return List(items.length()) { i -> items.getJSONObject(i).let { Score(it.getString("id"), it.getString("title"), it.getString("fileName"), it.getString("relativePath"), it.getString("mimeType"), it.getLong("importedAt"), it.optBoolean("isFavorite")) } }
    }

    fun validate(source: Uri): Boolean = context.contentResolver.openInputStream(source)?.use { input ->
        ZipInputStream(input).use { zip -> generateSequence { zip.nextEntry }.any { it.name == "library.json" } }
    } ?: false
}
