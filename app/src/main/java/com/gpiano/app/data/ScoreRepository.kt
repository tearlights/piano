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
            cleanupRestoreGenerations(previousPaths)
            val generation = UUID.randomUUID().toString()
            val restoredSnapshot = remapBackupFilePaths(staged.snapshot, generation)
            val restoredPaths = backupManager.referencedPaths(restoredSnapshot)
            val sourceByTarget = staged.dataPaths.associateBy { restoreGenerationPath(generation, it) }
            val files = installStagedFiles(staged, sourceByTarget)
            try {
                database.backupRestoreDao().replaceWith(restoredSnapshot)
                files.complete()
                (previousPaths - restoredPaths).forEach { stalePath ->
                    runCatching { resolveRepositoryPath(context.filesDir, stalePath).delete() }
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
        val groupDirectory = resolveRepositoryPath(context.filesDir, "scores/$id")
        try {
            val pages = uris.mapIndexed { index, uri ->
                val type = resolver.getType(uri).orEmpty()
                require(type.startsWith("image/")) { "仅支持图片" }
                val destination = resolveRepositoryPath(
                    context.filesDir,
                    "scores/$id/page-${index + 1}.${extensionFor(type)}",
                )
                destination.parentFile?.mkdirs()
                resolver.openInputStream(uri)?.use { input -> destination.outputStream().use(input::copyTo) }
                    ?: error("无法读取图片")
                ScorePage(id, index, index, destination.relativeTo(context.filesDir).path)
            }
            val firstName = resolver.query(uris.first(), arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                cursor.takeIf { it.moveToFirst() }?.getString(cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME))
            } ?: "图片琴谱"
            val title = firstName.substringBeforeLast('.', firstName)
            val score = Score(id, title, title, "", "application/x-gpiano-image-group", System.currentTimeMillis())
            database.withTransaction {
                database.scoreDao().upsert(score)
                database.scorePageDao().insertAll(pages)
            }
            score
        } catch (error: Throwable) {
            groupDirectory.deleteRecursively()
            throw error
        }
    }

    suspend fun import(uri: Uri): Score = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val mimeType = resolver.getType(uri).orEmpty()
        require(mimeType == "application/pdf" || mimeType.startsWith("image/")) { "仅支持 PDF 或图片琴谱" }

        val displayName = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            cursor.takeIf { it.moveToFirst() }?.getString(cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME))
        } ?: "未命名琴谱"
        val extension = extensionFor(mimeType)
        scoreDirectory.mkdirs()
        val id = UUID.randomUUID().toString()
        val destination = File(scoreDirectory, "$id.$extension")

        val score = Score(
            id = id,
            title = displayName.substringBeforeLast('.', displayName),
            fileName = displayName,
            relativePath = "scores/${destination.name}",
            mimeType = mimeType,
            importedAt = System.currentTimeMillis(),
        )
        try {
            resolver.openInputStream(uri)?.use { input -> destination.outputStream().use(input::copyTo) }
                ?: error("无法读取所选文件")
            val pages = List(if (mimeType == "application/pdf") pdfPageCount(destination) else 1) { index ->
                ScorePage(score.id, index, index)
            }
            database.withTransaction {
                database.scoreDao().upsert(score)
                database.scorePageDao().insertAll(pages)
            }
            score
        } catch (error: Throwable) {
            destination.delete()
            throw error
        }
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
        val pageFiles = pages.mapNotNull { page -> page.relativePath?.let { resolveRepositoryPath(context.filesDir, it) } }
        val scoreFile = score.relativePath.takeIf(String::isNotBlank)?.let { resolveRepositoryPath(context.filesDir, it) }
        val scoreGroupDirectory = resolveRepositoryPath(context.filesDir, "scores/${score.id}")
        database.withTransaction {
            database.bookmarkDao().deleteForScore(score.id)
            database.scorePageDao().deleteForScore(score.id)
            database.scoreDao().delete(score.id)
        }
        pageFiles.forEach(File::delete)
        scoreFile?.delete()
        scoreGroupDirectory.deleteRecursively()
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

    private fun installStagedFiles(staged: StagedGpianoBackup, sourceByTarget: Map<String, String>): BackupFileCommit {
        val rollbackRoot = File(context.cacheDir, "backup-rollback/${UUID.randomUUID()}")
        check(rollbackRoot.mkdirs()) { "无法创建恢复回滚目录" }
        val changes = mutableListOf<BackupFileChange>()
        try {
            sourceByTarget.forEach { (targetPath, sourcePath) ->
                val source = resolveRepositoryPath(staged.directory, sourcePath)
                val target = resolveRepositoryPath(context.filesDir, targetPath)
                target.parentFile?.mkdirs()
                check(!target.exists()) { "恢复 generation 路径发生冲突" }
                changes += BackupFileChange(target, null)
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

    private fun cleanupRestoreGenerations(referencedPaths: Set<String>) {
        val root = File(context.filesDir, RESTORE_GENERATIONS)
        val referencedGenerations = referencedPaths.mapNotNull { path ->
            path.takeIf { it.startsWith("$RESTORE_GENERATIONS/") }?.substringAfter('/')?.substringBefore('/')
        }.toSet()
        root.listFiles()?.filter { it.isDirectory && it.name !in referencedGenerations }?.forEach(File::deleteRecursively)
    }

}

internal fun restoreGenerationPath(generation: String, originalPath: String): String {
    require(generation.matches(Regex("[0-9a-f-]{36}"))) { "恢复 generation 标识无效" }
    require(originalPath.isNotBlank() && !File(originalPath).isAbsolute) { "恢复源路径无效" }
    val normalized = originalPath.replace('\\', '/')
    require(normalized.split('/').none { it.isBlank() || it == "." || it == ".." }) { "恢复源路径无效" }
    return "$RESTORE_GENERATIONS/$generation/$normalized"
}

internal fun remapBackupFilePaths(snapshot: GpianoBackupSnapshot, generation: String): GpianoBackupSnapshot {
    fun path(value: String): String = value.takeIf(String::isNotBlank)?.let { restoreGenerationPath(generation, it) }.orEmpty()
    fun nullablePath(value: String?): String? = value?.let { restoreGenerationPath(generation, it) }
    return snapshot.copy(
        scores = snapshot.scores.map { it.copy(relativePath = path(it.relativePath)) },
        pages = snapshot.pages.map { it.copy(relativePath = nullablePath(it.relativePath)) },
        structures = snapshot.structures.map { it.copy(sourceMapRelativePath = nullablePath(it.sourceMapRelativePath)) },
        revisions = snapshot.revisions.map { it.copy(musicXmlRelativePath = path(it.musicXmlRelativePath)) },
        practiceVersions = snapshot.practiceVersions.map { it.copy(musicXmlRelativePath = path(it.musicXmlRelativePath)) },
        practiceVersionRevisions = snapshot.practiceVersionRevisions.map { it.copy(musicXmlRelativePath = path(it.musicXmlRelativePath)) },
    )
}

private const val RESTORE_GENERATIONS = "restore-generations"

internal fun resolveRepositoryPath(root: File, relativePath: String): File {
    require(relativePath.isNotBlank() && !File(relativePath).isAbsolute) { "文件路径无效" }
    val canonicalRoot = root.canonicalFile
    val resolved = File(canonicalRoot, relativePath).canonicalFile
    require(resolved.path.startsWith(canonicalRoot.path + File.separator)) { "文件路径越界" }
    return resolved
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
