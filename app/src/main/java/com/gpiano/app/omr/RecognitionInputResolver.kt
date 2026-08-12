package com.gpiano.app.omr

import android.content.Context
import com.gpiano.app.data.GpianoDatabase
import com.gpiano.app.data.Score
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

data class RecognitionInput(
    val score: Score,
    val pageIndex: Int,
    val file: File,
    val mimeType: String,
    val sha256: String,
)

class RecognitionInputResolver(
    context: Context,
    private val database: GpianoDatabase,
) {
    private val filesDir = context.applicationContext.filesDir.canonicalFile

    suspend fun resolve(scoreId: String, pageIndex: Int): RecognitionInput {
        val score = database.scoreDao().find(scoreId) ?: error("原谱已被删除")
        require(pageIndex >= 0) { "识别页码无效" }
        val relativePath = if (score.mimeType == IMAGE_GROUP_MIME) {
            database.scorePageDao().find(score.id, pageIndex)?.relativePath ?: error("找不到所选图片页")
        } else {
            require(pageIndex == 0) { "当前仅支持单页图片转换" }
            score.relativePath
        }
        val file = resolveInside(relativePath)
        require(file.isFile) { "原谱图片不存在" }
        val mimeType = when (file.extension.lowercase()) {
            "png" -> "image/png"
            "webp" -> "image/webp"
            "jpg", "jpeg" -> "image/jpeg"
            else -> score.mimeType
        }
        require(mimeType in SUPPORTED_IMAGE_TYPES) { "当前 OMR 仅支持单页 PNG、JPEG 或 WebP 图片" }
        return RecognitionInput(score, pageIndex, file, mimeType, file.sha256())
    }

    private fun resolveInside(relativePath: String): File {
        require(relativePath.isNotBlank() && !File(relativePath).isAbsolute) { "原谱文件路径无效" }
        val file = File(filesDir, relativePath).canonicalFile
        require(file.path.startsWith(filesDir.path + File.separator)) { "原谱文件路径越界" }
        return file
    }

    private companion object {
        const val IMAGE_GROUP_MIME = "application/x-gpiano-image-group"
        val SUPPORTED_IMAGE_TYPES = setOf("image/png", "image/jpeg", "image/webp")
    }
}

internal fun File.sha256(): String {
    val digest = MessageDigest.getInstance("SHA-256")
    FileInputStream(this).buffered().use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

