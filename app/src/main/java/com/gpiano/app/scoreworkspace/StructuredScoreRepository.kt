package com.gpiano.app.scoreworkspace

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import com.gpiano.app.data.GpianoDatabase
import com.gpiano.app.data.GpianoDatabaseProvider
import com.gpiano.app.data.Score
import com.gpiano.app.data.ScorePage
import com.gpiano.app.data.ScoreRevision
import com.gpiano.app.data.ScoreStructure
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

data class PersistentScoreSession(
    val structure: ScoreStructure,
    val revision: ScoreRevision,
    val document: MusicXmlDocument,
    val revisions: List<ScoreRevision> = emptyList(),
    val redoRevisionId: String? = null,
    val sourcePage: SourceScorePage? = null,
    val recoveryMessage: String? = null,
) {
    val xml: String get() = document.xml
    val score: ScoreIr get() = document.score
    val revisionNumber: Int get() = revision.revisionNumber
    val canUndo: Boolean get() = revision.parentRevisionId != null
    val canRedo: Boolean get() = redoRevisionId != null
}

data class SourceScorePage(
    val score: Score,
    val page: ScorePage?,
)

class StructuredScoreRepository(
    context: Context,
    private val database: GpianoDatabase = GpianoDatabaseProvider.get(context),
) {
    private val appContext = context.applicationContext
    private val dao = database.scoreStructureDao()

    suspend fun openOrCreate(
        sourceKey: String,
        initialDocument: MusicXmlDocument,
        sourceScoreId: String? = null,
        recognitionStatus: String = "source",
        recognitionConfidence: Double? = null,
    ): PersistentScoreSession = withContext(Dispatchers.IO) {
        require(sourceKey.isNotBlank()) { "结构化乐谱来源标识不能为空" }
        dao.findBySourceKey(sourceKey)?.let { return@withContext loadValidSession(it) }

        ScoreIrValidator.requireValid(initialDocument.score)
        val structureId = UUID.randomUUID().toString()
        val revisionId = UUID.randomUUID().toString()
        val relativePath = revisionPath(structureId, revisionId)
        val writtenFile = writeNewRevisionFile(relativePath, initialDocument.xml)
        val now = System.currentTimeMillis()
        val structure = ScoreStructure(
            id = structureId,
            sourceScoreId = sourceScoreId,
            sourceKey = sourceKey,
            title = initialDocument.score.title.ifBlank { initialDocument.sourceName },
            formatVersion = 1,
            currentRevisionId = revisionId,
            recognitionStatus = recognitionStatus,
            recognitionConfidence = recognitionConfidence,
            sourceMapRelativePath = null,
            createdAt = now,
            updatedAt = now,
        )
        val revision = ScoreRevision(
            id = revisionId,
            structureId = structureId,
            parentRevisionId = null,
            revisionNumber = 0,
            kind = if (recognitionStatus == "source") "source" else "omr",
            musicXmlRelativePath = relativePath,
            operationJson = null,
            createdAt = now,
        )

        try {
            var concurrentlyCreated: ScoreStructure? = null
            database.withTransaction {
                concurrentlyCreated = dao.findBySourceKey(sourceKey)
                if (concurrentlyCreated == null) {
                    dao.insertStructure(structure)
                    dao.insertRevision(revision)
                }
            }
            concurrentlyCreated?.let {
                writtenFile.delete()
                return@withContext loadValidSession(it)
            }
            createSession(structure, revision, initialDocument)
        } catch (error: Throwable) {
            writtenFile.delete()
            throw error
        }
    }

    suspend fun apply(
        session: PersistentScoreSession,
        operation: CorrectionOperation,
    ): PersistentScoreSession = withContext(Dispatchers.IO) {
        val compilation = MusicXmlRevisionCompiler.apply(session.xml, operation)
        ScoreIrValidator.requireValid(compilation.score)
        val revisionId = UUID.randomUUID().toString()
        val relativePath = revisionPath(session.structure.id, revisionId)
        val writtenFile = writeNewRevisionFile(relativePath, compilation.xml)
        val now = System.currentTimeMillis()
        lateinit var revision: ScoreRevision
        lateinit var updatedStructure: ScoreStructure

        try {
            database.withTransaction {
                val stored = dao.findStructure(session.structure.id)
                    ?: error("结构化乐谱已被删除")
                check(stored.currentRevisionId == session.revision.id) {
                    "乐谱已在其他位置更新，请重新打开后再修改"
                }
                revision = ScoreRevision(
                    id = revisionId,
                    structureId = stored.id,
                    parentRevisionId = session.revision.id,
                    revisionNumber = dao.maxRevisionNumber(stored.id) + 1,
                    kind = "user",
                    musicXmlRelativePath = relativePath,
                    operationJson = operation.toAuditJson(),
                    createdAt = now,
                )
                updatedStructure = stored.copy(currentRevisionId = revisionId, updatedAt = now)
                dao.insertRevision(revision)
                dao.updateStructure(updatedStructure)
            }
        } catch (error: Throwable) {
            writtenFile.delete()
            throw error
        }

        createSession(
            structure = updatedStructure,
            revision = revision,
            document = MusicXmlDocument(
                sourceName = session.document.sourceName,
                xml = compilation.xml,
                score = compilation.score,
                summary = compilation.score.toSummary(),
            ),
        )
    }

    suspend fun undo(session: PersistentScoreSession): PersistentScoreSession = withContext(Dispatchers.IO) {
        val parentId = session.revision.parentRevisionId ?: return@withContext session
        val parent = dao.findRevision(parentId) ?: error("找不到上一修订版")
        val parentDocument = readDocument(parent, session.document.sourceName)
        val now = System.currentTimeMillis()
        lateinit var updated: ScoreStructure
        database.withTransaction {
            val stored = dao.findStructure(session.structure.id) ?: error("结构化乐谱已被删除")
            check(stored.currentRevisionId == session.revision.id) {
                "乐谱已在其他位置更新，请重新打开后再撤销"
            }
            updated = stored.copy(currentRevisionId = parent.id, updatedAt = now)
            dao.updateStructure(updated)
        }
        createSession(updated, parent, parentDocument, redoRevisionId = session.revision.id)
    }

    suspend fun redo(session: PersistentScoreSession): PersistentScoreSession = withContext(Dispatchers.IO) {
        val child = session.redoRevisionId?.let { dao.findRevision(it) }
            ?.takeIf { it.structureId == session.structure.id && it.parentRevisionId == session.revision.id }
            ?: dao.latestChild(session.structure.id, session.revision.id)
            ?: return@withContext session
        checkoutInternal(session, child)
    }

    suspend fun checkout(session: PersistentScoreSession, revisionId: String): PersistentScoreSession =
        withContext(Dispatchers.IO) {
            val revision = dao.findRevision(revisionId) ?: error("修订版本不存在")
            require(revision.structureId == session.structure.id) { "修订版本不属于当前乐谱" }
            checkoutInternal(session, revision)
        }

    suspend fun open(structureId: String): PersistentScoreSession = withContext(Dispatchers.IO) {
        val structure = dao.findStructure(structureId) ?: error("结构化乐谱不存在")
        loadValidSession(structure)
    }

    suspend fun export(session: PersistentScoreSession, destination: Uri) = withContext(Dispatchers.IO) {
        val storedRevision = dao.findRevision(session.revision.id) ?: error("当前修订版本不存在")
        require(storedRevision.structureId == session.structure.id) { "当前修订不属于这份结构化乐谱" }
        ScoreIrValidator.requireValid(MusicXmlScoreParser.parse(session.xml))
        appContext.contentResolver.openOutputStream(destination, "w")?.use { output ->
            output.write(session.xml.toByteArray(Charsets.UTF_8))
            output.flush()
        } ?: error("无法写入 MusicXML")
    }

    private suspend fun loadValidSession(structure: ScoreStructure): PersistentScoreSession {
        val visited = mutableSetOf<String>()
        var candidate = dao.findRevision(structure.currentRevisionId)
        while (candidate != null && visited.add(candidate.id)) {
            val result = runCatching { readDocument(candidate, structure.sourceKey) }
            if (result.isSuccess) {
                val recovered = candidate.id != structure.currentRevisionId
                val updated = if (recovered) {
                    structure.copy(currentRevisionId = candidate.id, updatedAt = System.currentTimeMillis())
                        .also { dao.updateStructure(it) }
                } else {
                    structure
                }
                return createSession(
                    structure = updated,
                    revision = candidate,
                    document = result.getOrThrow(),
                    recoveryMessage = if (recovered) "当前修订损坏，已恢复到最近一个有效版本" else null,
                )
            }
            candidate = candidate.parentRevisionId?.let { dao.findRevision(it) }
        }

        for (fallback in dao.revisionsNewestFirst(structure.id)) {
            val document = runCatching { readDocument(fallback, structure.sourceKey) }.getOrNull() ?: continue
            val updated = structure.copy(currentRevisionId = fallback.id, updatedAt = System.currentTimeMillis())
            dao.updateStructure(updated)
            return createSession(
                structure = updated,
                revision = fallback,
                document = document,
                recoveryMessage = "修订指针无效，已恢复到最近一个有效版本",
            )
        }
        error("结构化乐谱没有可读取的 MusicXML 修订版")
    }

    private suspend fun checkoutInternal(
        session: PersistentScoreSession,
        revision: ScoreRevision,
    ): PersistentScoreSession {
        val document = readDocument(revision, session.document.sourceName)
        val now = System.currentTimeMillis()
        lateinit var updated: ScoreStructure
        database.withTransaction {
            val stored = dao.findStructure(session.structure.id) ?: error("结构化乐谱已被删除")
            check(stored.currentRevisionId == session.revision.id) {
                "乐谱已在其他位置更新，请重新打开后再切换版本"
            }
            updated = stored.copy(currentRevisionId = revision.id, updatedAt = now)
            dao.updateStructure(updated)
        }
        return createSession(updated, revision, document)
    }

    private suspend fun createSession(
        structure: ScoreStructure,
        revision: ScoreRevision,
        document: MusicXmlDocument,
        redoRevisionId: String? = null,
        recoveryMessage: String? = null,
    ): PersistentScoreSession {
        val detectedRedo = redoRevisionId ?: dao.latestChild(structure.id, revision.id)?.id
        val source = structure.sourceScoreId?.let { scoreId ->
            val score = database.scoreDao().find(scoreId) ?: return@let null
            val pageIndex = database.recognitionJobDao().latestForResult(structure.id)?.sourcePageIndex ?: 0
            SourceScorePage(score, database.scorePageDao().find(scoreId, pageIndex))
        }
        return PersistentScoreSession(
            structure = structure,
            revision = revision,
            document = document,
            revisions = dao.revisionsNewestFirst(structure.id),
            redoRevisionId = detectedRedo,
            sourcePage = source,
            recoveryMessage = recoveryMessage,
        )
    }

    private fun readDocument(revision: ScoreRevision, sourceName: String): MusicXmlDocument {
        val file = resolveRelativePath(revision.musicXmlRelativePath)
        require(file.isFile) { "修订文件不存在：${revision.revisionNumber}" }
        val xml = MusicXmlCompatibilityNormalizer.normalize(file.readText(Charsets.UTF_8))
        val score = MusicXmlScoreParser.parse(xml)
        ScoreIrValidator.requireValid(score)
        return MusicXmlDocument(sourceName, xml, score, score.toSummary())
    }

    private fun writeNewRevisionFile(relativePath: String, xml: String): File {
        val parsed = MusicXmlScoreParser.parse(xml)
        ScoreIrValidator.requireValid(parsed)
        val target = resolveRelativePath(relativePath)
        require(!target.exists()) { "修订文件已存在" }
        target.parentFile?.mkdirs()
        val temporary = File(target.parentFile, ".${target.name}.${UUID.randomUUID()}.tmp")
        try {
            FileOutputStream(temporary).use { output ->
                output.write(xml.toByteArray(Charsets.UTF_8))
                output.fd.sync()
            }
            check(temporary.renameTo(target)) { "无法提交 MusicXML 修订文件" }
            return target
        } finally {
            temporary.delete()
        }
    }

    private fun resolveRelativePath(relativePath: String): File {
        require(relativePath.isNotBlank() && !File(relativePath).isAbsolute) { "无效的结构化文件路径" }
        val root = appContext.filesDir.canonicalFile
        val resolved = File(root, relativePath).canonicalFile
        require(resolved.path.startsWith(root.path + File.separator)) { "结构化文件路径越界" }
        return resolved
    }

    private fun revisionPath(structureId: String, revisionId: String): String =
        "structures/$structureId/revisions/$revisionId.musicxml"
}

private fun CorrectionOperation.toAuditJson(): String = when (this) {
    is CorrectionOperation.ChangePitch -> JSONObject()
        .put("type", "changePitch")
        .put("eventId", eventId)
        .put(
            "pitch",
            JSONObject()
                .put("step", pitch.step.toString())
                .put("alter", pitch.alter)
                .put("octave", pitch.octave),
        )
        .toString()
    is CorrectionOperation.ChangeDuration -> JSONObject()
        .put("type", "changeDuration")
        .put("eventId", eventId)
        .put("noteType", duration.noteType)
        .put("dots", duration.dots)
        .put("actualNotes", duration.actualNotes)
        .put("normalNotes", duration.normalNotes)
        .toString()
    is CorrectionOperation.ChangeRest -> JSONObject()
        .put("type", "changeRest")
        .put("eventId", eventId)
        .put("makeRest", makeRest)
        .put("pitch", pitchWhenNote?.displayName)
        .toString()
    is CorrectionOperation.SetTieToNext -> JSONObject()
        .put("type", "setTieToNext")
        .put("eventId", eventId)
        .put("enabled", enabled)
        .toString()
}
