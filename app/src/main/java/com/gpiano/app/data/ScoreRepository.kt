package com.gpiano.app.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.room.Room
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

class ScoreRepository(private val context: Context) {
    private val database = Room.databaseBuilder(context, GpianoDatabase::class.java, "gpiano.db").addMigrations(GpianoDatabaseMigration.V1_TO_V2, GpianoDatabaseMigration.V2_TO_V3, GpianoDatabaseMigration.V3_TO_V4, GpianoDatabaseMigration.V4_TO_V5, GpianoDatabaseMigration.V5_TO_V6).build()
    private val scoreDirectory = File(context.filesDir, "scores")

    fun observeScores(): Flow<List<Score>> = database.scoreDao().observeAll()

    suspend fun restoreBackup(uri: Uri) = withContext(Dispatchers.IO) {
        val restored = GpianoBackupManager(context).restore(uri)
        restored.forEach { database.scoreDao().upsert(it) }
    }


    suspend fun importImageGroup(uris: List<Uri>): Score = withContext(Dispatchers.IO) {
        require(uris.isNotEmpty()) { "未选择图片" }
        val resolver = context.contentResolver
        val id = UUID.randomUUID().toString()
        val pages = uris.mapIndexed { index, uri ->
            val type = resolver.getType(uri).orEmpty()
            require(type.startsWith("image/")) { "仅支持图片" }
            val destination = File(context.filesDir, "scores/" + id + "/page-" + (index + 1) + "." + extensionFor(type))
            destination.parentFile?.mkdirs()
            resolver.openInputStream(uri)?.use { input -> destination.outputStream().use(input::copyTo) } ?: error("无法读取图片")
            ScorePage(id, index, index, destination.relativeTo(context.filesDir).path)
        }
        val firstName = resolver.query(uris.first(), arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            cursor.takeIf { it.moveToFirst() }?.getString(cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME))
        } ?: "图片琴谱"
        val title = firstName.substringBeforeLast('.', firstName)
        val score = Score(id, title, title, "", "application/x-gpiano-image-group", System.currentTimeMillis())
        database.scoreDao().upsert(score)
        database.scorePageDao().insertAll(pages)
        score
    }

    suspend fun import(uri: Uri): Score = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val mimeType = resolver.getType(uri).orEmpty()
        require(mimeType == "application/pdf" || mimeType.startsWith("image/")) { "仅支持 PDF 或图片琴谱" }

        val displayName = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            cursor.takeIf { it.moveToFirst() }?.getString(cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME))
        } ?: "未命名琴谱"
        val extension = displayName.substringAfterLast('.', missingDelimiterValue = extensionFor(mimeType))
        scoreDirectory.mkdirs()
        val id = UUID.randomUUID().toString()
        val destination = File(scoreDirectory, "$id.$extension")

        resolver.openInputStream(uri)?.use { input -> destination.outputStream().use(input::copyTo) }
            ?: error("无法读取所选文件")

        val score = Score(
            id = id,
            title = displayName.substringBeforeLast('.', displayName),
            fileName = displayName,
            relativePath = "scores/${destination.name}",
            mimeType = mimeType,
            importedAt = System.currentTimeMillis(),
        )
        database.scoreDao().upsert(score)
        database.scorePageDao().insertAll(List(if (mimeType == "application/pdf") pdfPageCount(destination) else 1) { index -> ScorePage(score.id, index, index) })
        score
    }

    suspend fun moveToFolder(scoreId: String, folderId: String?) = database.scoreDao().setFolder(scoreId, folderId)

    suspend fun rename(scoreId: String, title: String) {
        require(title.isNotBlank()) { "琴谱名称不能为空" }
        database.scoreDao().rename(scoreId, title.trim())
    }

    fun observeFolders(): Flow<List<Folder>> = database.folderDao().observeAll()

    suspend fun createFolder(name: String) {
        require(name.isNotBlank()) { "文件夹名称不能为空" }
        database.folderDao().insert(Folder(java.util.UUID.randomUUID().toString(), name.trim(), System.currentTimeMillis()))
    }

    suspend fun deleteFolder(id: String) = database.folderDao().delete(id)

    fun observeBookmarks(scoreId: String): Flow<List<Bookmark>> = database.bookmarkDao().observe(scoreId)

    suspend fun toggleBookmark(scoreId: String, page: Int) {
        if (database.bookmarkDao().exists(scoreId, page)) database.bookmarkDao().delete(scoreId, page) else database.bookmarkDao().insert(Bookmark(scoreId, page, System.currentTimeMillis()))
    }

    fun observePages(scoreId: String): Flow<List<ScorePage>> = database.scorePageDao().observe(scoreId)

    suspend fun movePage(scoreId: String, displayIndex: Int, direction: Int) {
        val pages = database.scorePageDao().observe(scoreId).first()
        val current = pages.indexOfFirst { it.displayIndex == displayIndex }
        val target = current + direction
        if (current >= 0 && target in pages.indices) database.scorePageDao().swap(scoreId, pages[current], pages[target])
    }

    suspend fun restorePageOrder(scoreId: String) = database.scorePageDao().restoreOriginalOrder(scoreId)

    fun observeFavorites(): Flow<List<Score>> = database.scoreDao().observeFavorites()

    suspend fun toggleFavorite(scoreId: String) = database.scoreDao().toggleFavorite(scoreId)

    suspend fun delete(score: Score) = withContext(Dispatchers.IO) {
        val pages = database.scorePageDao().observe(score.id).first()
        pages.forEach { page -> page.relativePath?.let { File(context.filesDir, it).delete() } }
        database.scorePageDao().deleteForScore(score.id)
        database.scoreDao().delete(score.id)
        if (score.relativePath.isNotBlank()) File(context.filesDir, score.relativePath).delete()
        File(context.filesDir, "scores/${score.id}").delete()
    }

    suspend fun markOpened(score: Score) = database.scoreDao().markOpened(score.id, System.currentTimeMillis())

    private fun pdfPageCount(file: File): Int = android.os.ParcelFileDescriptor.open(file, android.os.ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor -> android.graphics.pdf.PdfRenderer(descriptor).use { it.pageCount } }

    private fun extensionFor(mimeType: String): String = when (mimeType) {
        "application/pdf" -> "pdf"
        "image/png" -> "png"
        "image/webp" -> "webp"
        else -> "jpg"
    }
}
