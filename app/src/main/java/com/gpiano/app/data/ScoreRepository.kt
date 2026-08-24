package com.gpiano.app.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.room.withTransaction
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

class ScoreRepository(private val context: Context) {
    private val database = GpianoDatabaseProvider.get(context)
    private val scoreDirectory = File(context.filesDir, "scores")

    fun observeScores(): Flow<List<Score>> = database.scoreDao().observeAll()

    suspend fun exportBackup(uri: Uri) = withContext(Dispatchers.IO) {
        val snapshot = currentSnapshot()
        GpianoBackupManager(context).export(snapshot, uri)
    }

    suspend fun restoreBackup(uri: Uri) = withContext(Dispatchers.IO) {
        val backupManager = GpianoBackupManager(context)
        backupManager.stage(uri).use { staged ->
            val previousPaths = backupManager.referencedPaths(currentSnapshot())
            val files = installStagedFiles(staged)
            try {
                val snapshot = staged.snapshot
                database.backupRestoreDao().replaceWith(snapshot)
                files.complete()
                (previousPaths - staged.dataPaths).forEach { stalePath ->
                    runCatching { resolveInside(context.filesDir, stalePath).delete() }
                }
            } catch (error: Throwable) {
                files.rollback()
                throw error
            }
        }
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

    suspend fun deleteFolder(id: String) = database.withTransaction {
        database.scoreDao().clearFolder(id)
        database.folderDao().delete(id)
    }

    fun observeBookmarks(scoreId: String): Flow<List<Bookmark>> = database.bookmarkDao().observe(scoreId)

    suspend fun toggleBookmark(scoreId: String, page: Int) {
        database.bookmarkDao().toggle(scoreId, page, System.currentTimeMillis())
    }

    fun observePages(scoreId: String): Flow<List<ScorePage>> = database.scorePageDao().observe(scoreId)

    suspend fun movePage(scoreId: String, displayIndex: Int, direction: Int) {
        database.scorePageDao().move(scoreId, displayIndex, direction)
    }

    suspend fun restorePageOrder(scoreId: String) = database.scorePageDao().restoreOriginalOrder(scoreId)

    fun observeFavorites(): Flow<List<Score>> = database.scoreDao().observeFavorites()

    suspend fun toggleFavorite(scoreId: String) = database.scoreDao().toggleFavorite(scoreId)

    suspend fun delete(score: Score) = withContext(Dispatchers.IO) {
        val pages = database.scorePageDao().observe(score.id).first()
        database.withTransaction {
            database.bookmarkDao().deleteForScore(score.id)
            database.scorePageDao().deleteForScore(score.id)
            database.scoreDao().delete(score.id)
        }
        pages.forEach { page -> page.relativePath?.let { File(context.filesDir, it).delete() } }
        if (score.relativePath.isNotBlank()) File(context.filesDir, score.relativePath).delete()
        File(context.filesDir, "scores/${score.id}").delete()
    }

    suspend fun markOpened(score: Score) = database.scoreDao().markOpened(score.id, System.currentTimeMillis())

    private suspend fun currentSnapshot(): GpianoBackupSnapshot = database.withTransaction {
        GpianoBackupSnapshot(
            scores = database.scoreDao().getAll(),
            pages = database.scorePageDao().getAll(),
            bookmarks = database.bookmarkDao().getAll(),
            folders = database.folderDao().getAll(),
            structures = database.scoreStructureDao().allStructures(),
            revisions = database.scoreStructureDao().allRevisions(),
            recognitionJobs = database.recognitionJobDao().getAll(),
            practiceVersions = database.practiceVersionDao().getAll(),
            practiceAttempts = database.practiceAttemptDao().getAll(),
            midiPerformanceEvents = database.practiceAttemptDao().getAllEvents(),
            practiceVersionRevisions = database.practiceVersionDao().getAllRevisions(),
        )
    }

    private fun pdfPageCount(file: File): Int = android.os.ParcelFileDescriptor.open(file, android.os.ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor -> android.graphics.pdf.PdfRenderer(descriptor).use { it.pageCount } }

    private fun extensionFor(mimeType: String): String = when (mimeType) {
        "application/pdf" -> "pdf"
        "image/png" -> "png"
        "image/webp" -> "webp"
        else -> "jpg"
    }

    private fun installStagedFiles(staged: StagedGpianoBackup): BackupFileCommit {
        val rollbackRoot = File(context.cacheDir, "backup-rollback/${UUID.randomUUID()}")
        check(rollbackRoot.mkdirs()) { "无法创建恢复回滚目录" }
        val changes = mutableListOf<BackupFileChange>()
        try {
            staged.dataPaths.forEach { relativePath ->
                val source = resolveInside(staged.directory, relativePath)
                val target = resolveInside(context.filesDir, relativePath)
                target.parentFile?.mkdirs()
                val rollback = if (target.exists()) {
                    resolveInside(rollbackRoot, relativePath).also { backup ->
                        backup.parentFile?.mkdirs()
                        target.inputStream().buffered().use { input ->
                            FileOutputStream(backup).buffered().use { input.copyTo(it) }
                        }
                    }
                } else {
                    null
                }
                changes += BackupFileChange(target, rollback)
                val temporary = File(target.parentFile, ".${target.name}.${UUID.randomUUID()}.restore")
                try {
                    FileOutputStream(temporary).use { output ->
                        source.inputStream().buffered().use { it.copyTo(output) }
                        output.fd.sync()
                    }
                    runCatching {
                        Files.move(
                            temporary.toPath(),
                            target.toPath(),
                            StandardCopyOption.ATOMIC_MOVE,
                            StandardCopyOption.REPLACE_EXISTING,
                        )
                    }.getOrElse {
                        Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
                    }
                } finally {
                    temporary.delete()
                }
            }
            return BackupFileCommit(changes, rollbackRoot)
        } catch (error: Throwable) {
            BackupFileCommit(changes, rollbackRoot).rollback()
            throw error
        }
    }

    private fun resolveInside(root: File, relativePath: String): File {
        require(relativePath.isNotBlank() && !File(relativePath).isAbsolute) { "恢复文件路径无效" }
        val canonicalRoot = root.canonicalFile
        val resolved = File(canonicalRoot, relativePath).canonicalFile
        require(resolved.path.startsWith(canonicalRoot.path + File.separator)) { "恢复文件路径越界" }
        return resolved
    }
}

private data class BackupFileChange(val target: File, val rollback: File?)

private class BackupFileCommit(
    private val changes: List<BackupFileChange>,
    private val rollbackRoot: File,
) {
    fun complete() {
        rollbackRoot.deleteRecursively()
    }

    fun rollback() {
        changes.asReversed().forEach { change ->
            runCatching {
                val backup = change.rollback
                if (backup == null) {
                    change.target.delete()
                } else if (backup.exists()) {
                    change.target.parentFile?.mkdirs()
                    Files.copy(backup.toPath(), change.target.toPath(), StandardCopyOption.REPLACE_EXISTING)
                }
            }
        }
        rollbackRoot.deleteRecursively()
    }
}
