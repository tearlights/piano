package com.gpiano.app.scoreworkspace

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import com.gpiano.app.data.GpianoDatabase
import com.gpiano.app.data.GpianoDatabaseProvider
import com.gpiano.app.data.PracticeVersion
import com.gpiano.app.data.PracticeVersionRevision
import com.gpiano.app.data.PracticeVersionStatus
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class PracticeVersionDocument(
    val version: PracticeVersion,
    val document: MusicXmlDocument,
    val plan: PracticeEditPlan,
    val differences: List<PracticeDifference>,
    val revision: PracticeVersionRevision? = null,
    val revisions: List<PracticeVersionRevision> = emptyList(),
    val redoRevisionId: String? = null,
    val recoveryMessage: String? = null,
) {
    val xml: String get() = document.xml
    val score: ScoreIr get() = document.score
    val revisionNumber: Int get() = revision?.revisionNumber ?: 0
    val canUndo: Boolean get() = revision != null
    val canRedo: Boolean get() = redoRevisionId != null
}

class PracticeVersionRepository(
    context: Context,
    private val database: GpianoDatabase = GpianoDatabaseProvider.get(context),
) {
    private val appContext = context.applicationContext
    private val dao = database.practiceVersionDao()

    suspend fun list(structureId: String): List<PracticeVersion> = withContext(Dispatchers.IO) {
        dao.listForStructure(structureId)
    }

    suspend fun create(session: PersistentScoreSession, plan: PracticeEditPlan): PracticeVersionDocument =
        withContext(Dispatchers.IO) {
            require(plan.toMeasure <= session.score.measureCount) { "练习版本范围超出乐谱" }
            val compilation = PracticeVersionCompiler.compile(session.xml, plan)
            val id = UUID.randomUUID().toString()
            val relativePath = "structures/${session.structure.id}/practice-versions/$id.musicxml"
            val written = writeNewFile(relativePath, compilation.xml)
            val now = System.currentTimeMillis()
            val range = if (plan.fromMeasure == plan.toMeasure) {
                "第 ${plan.fromMeasure} 小节"
            } else {
                "第 ${plan.fromMeasure}–${plan.toMeasure} 小节"
            }
            val entity = PracticeVersion(
                id = id,
                structureId = session.structure.id,
                baseRevisionId = session.revision.id,
                title = "$range · ${plan.preset.displayName}",
                purpose = plan.preset.purpose,
                status = PracticeVersionStatus.Draft,
                fromMeasure = plan.fromMeasure,
                toMeasure = plan.toMeasure,
                musicXmlRelativePath = relativePath,
                currentRevisionId = null,
                planJson = plan.toJson(),
                differenceJson = PracticeVersionCompiler.differencesToJson(compilation.differences),
                createdAt = now,
                updatedAt = now,
            )
            try {
                database.withTransaction {
                    val structure = database.scoreStructureDao().findStructure(session.structure.id)
                        ?: error("结构化乐谱已被删除")
                    check(structure.currentRevisionId == session.revision.id) {
                        "主谱已在其他位置更新，请重新打开后再生成练习版本"
                    }
                    dao.insert(entity)
                }
            } catch (error: Throwable) {
                written.delete()
                throw error
            }
            entity.toDocument(compilation.xml, compilation.score)
        }

    suspend fun load(id: String): PracticeVersionDocument = withContext(Dispatchers.IO) {
        val version = dao.find(id) ?: error("练习版本不存在")
        loadValidDocument(version)
    }

    suspend fun apply(
        session: PracticeVersionDocument,
        operation: CorrectionOperation,
    ): PracticeVersionDocument = withContext(Dispatchers.IO) {
        val compilation = MusicXmlRevisionCompiler.apply(session.xml, operation)
        ScoreIrValidator.requireValid(compilation.score)
        val revisionId = UUID.randomUUID().toString()
        val relativePath = revisionPath(session.version.structureId, session.version.id, revisionId)
        val written = writeNewFile(relativePath, compilation.xml)
        val now = System.currentTimeMillis()
        lateinit var revision: PracticeVersionRevision
        lateinit var updatedVersion: PracticeVersion
        try {
            database.withTransaction {
                val stored = dao.find(session.version.id) ?: error("练习版本已被删除")
                check(stored.currentRevisionId == session.revision?.id) {
                    "练习版本已在其他位置更新，请重新打开后再修改"
                }
                revision = PracticeVersionRevision(
                    id = revisionId,
                    practiceVersionId = stored.id,
                    parentRevisionId = session.revision?.id,
                    revisionNumber = dao.maxRevisionNumber(stored.id) + 1,
                    kind = "user",
                    musicXmlRelativePath = relativePath,
                    operationJson = operation.toAuditJson(),
                    createdAt = now,
                )
                updatedVersion = stored.copy(currentRevisionId = revision.id, updatedAt = now)
                dao.insertRevision(revision)
                dao.setCurrentRevision(stored.id, revision.id, now)
            }
        } catch (error: Throwable) {
            written.delete()
            throw error
        }
        createDocument(
            version = updatedVersion,
            revision = revision,
            xml = compilation.xml,
            score = compilation.score,
        )
    }

    suspend fun undo(session: PracticeVersionDocument): PracticeVersionDocument = withContext(Dispatchers.IO) {
        val current = session.revision ?: return@withContext session
        val parent = current.parentRevisionId?.let { parentId ->
            dao.findRevision(parentId)?.also {
                require(it.practiceVersionId == session.version.id) { "上一修订版不属于当前练习版本" }
            } ?: error("找不到上一修订版")
        }
        val targetDocument = parent?.let { readRevision(it, session.document.sourceName) }
            ?: readBase(session.version)
        val now = System.currentTimeMillis()
        lateinit var updated: PracticeVersion
        database.withTransaction {
            val stored = dao.find(session.version.id) ?: error("练习版本已被删除")
            check(stored.currentRevisionId == current.id) {
                "练习版本已在其他位置更新，请重新打开后再撤销"
            }
            dao.setCurrentRevision(stored.id, parent?.id, now)
            updated = stored.copy(currentRevisionId = parent?.id, updatedAt = now)
        }
        createDocument(
            version = updated,
            revision = parent,
            xml = targetDocument.xml,
            score = targetDocument.score,
            redoRevisionId = current.id,
        )
    }

    suspend fun redo(session: PracticeVersionDocument): PracticeVersionDocument = withContext(Dispatchers.IO) {
        val requestedChild = session.redoRevisionId?.let { dao.findRevision(it) }
        val child = requestedChild
            ?.takeIf { it.practiceVersionId == session.version.id && it.parentRevisionId == session.revision?.id }
            ?: dao.latestChild(session.version.id, session.revision?.id)
            ?: return@withContext session
        checkoutInternal(session, child)
    }

    suspend fun checkout(
        session: PracticeVersionDocument,
        revisionId: String?,
    ): PracticeVersionDocument = withContext(Dispatchers.IO) {
        if (revisionId == null) return@withContext checkoutBase(session)
        val revision = dao.findRevision(revisionId) ?: error("练习版本修订不存在")
        require(revision.practiceVersionId == session.version.id) { "修订不属于当前练习版本" }
        checkoutInternal(session, revision)
    }

    suspend fun setStatus(id: String, status: String): PracticeVersionDocument = withContext(Dispatchers.IO) {
        require(status in PracticeVersionStatus.all) { "练习版本状态无效" }
        requireNotNull(dao.find(id)) { "练习版本不存在" }
        dao.setStatus(id, status, System.currentTimeMillis())
        load(id)
    }

    suspend fun export(id: String, destination: Uri) = withContext(Dispatchers.IO) {
        val current = load(id)
        ScoreIrValidator.requireValid(current.score)
        appContext.contentResolver.openOutputStream(destination)?.use { output ->
            output.write(current.xml.toByteArray(Charsets.UTF_8))
            output.flush()
        } ?: error("无法写入 MusicXML")
    }

    private suspend fun loadValidDocument(version: PracticeVersion): PracticeVersionDocument {
        val visited = mutableSetOf<String>()
        var candidate = version.currentRevisionId?.let { dao.findRevision(it) }
        while (candidate != null && candidate.practiceVersionId == version.id && visited.add(candidate.id)) {
            val result = runCatching { readRevision(candidate, version.title) }
            if (result.isSuccess) {
                val recovered = candidate.id != version.currentRevisionId
                val updated = if (recovered) {
                    val now = System.currentTimeMillis()
                    dao.setCurrentRevision(version.id, candidate.id, now)
                    version.copy(currentRevisionId = candidate.id, updatedAt = now)
                } else {
                    version
                }
                val document = result.getOrThrow()
                return createDocument(
                    version = updated,
                    revision = candidate,
                    xml = document.xml,
                    score = document.score,
                    recoveryMessage = if (recovered) "当前练习版本修订损坏，已恢复到最近一个有效版本" else null,
                )
            }
            candidate = candidate.parentRevisionId?.let { dao.findRevision(it) }
        }

        val base = runCatching { readBase(version) }.getOrNull()
        if (base != null) {
            val recovered = version.currentRevisionId != null
            val updated = if (recovered) {
                val now = System.currentTimeMillis()
                dao.setCurrentRevision(version.id, null, now)
                version.copy(currentRevisionId = null, updatedAt = now)
            } else {
                version
            }
            return createDocument(
                version = updated,
                revision = null,
                xml = base.xml,
                score = base.score,
                recoveryMessage = if (recovered) "当前练习版本修订损坏，已恢复到基础候选" else null,
            )
        }

        for (fallback in dao.revisionsNewestFirst(version.id)) {
            val document = runCatching { readRevision(fallback, version.title) }.getOrNull() ?: continue
            val now = System.currentTimeMillis()
            dao.setCurrentRevision(version.id, fallback.id, now)
            val updated = version.copy(currentRevisionId = fallback.id, updatedAt = now)
            return createDocument(
                version = updated,
                revision = fallback,
                xml = document.xml,
                score = document.score,
                recoveryMessage = "练习版本指针或基础候选无效，已恢复到最近一个有效修订",
            )
        }
        error("练习版本没有可读取的 MusicXML")
    }

    private suspend fun checkoutInternal(
        session: PracticeVersionDocument,
        revision: PracticeVersionRevision,
    ): PracticeVersionDocument {
        val document = readRevision(revision, session.document.sourceName)
        val now = System.currentTimeMillis()
        lateinit var updated: PracticeVersion
        database.withTransaction {
            val stored = dao.find(session.version.id) ?: error("练习版本已被删除")
            check(stored.currentRevisionId == session.revision?.id) {
                "练习版本已在其他位置更新，请重新打开后再切换版本"
            }
            dao.setCurrentRevision(stored.id, revision.id, now)
            updated = stored.copy(currentRevisionId = revision.id, updatedAt = now)
        }
        return createDocument(updated, revision, document.xml, document.score)
    }

    private suspend fun checkoutBase(session: PracticeVersionDocument): PracticeVersionDocument {
        if (session.revision == null) return session
        val document = readBase(session.version)
        val now = System.currentTimeMillis()
        lateinit var updated: PracticeVersion
        database.withTransaction {
            val stored = dao.find(session.version.id) ?: error("练习版本已被删除")
            check(stored.currentRevisionId == session.revision.id) {
                "练习版本已在其他位置更新，请重新打开后再切换基础候选"
            }
            dao.setCurrentRevision(stored.id, null, now)
            updated = stored.copy(currentRevisionId = null, updatedAt = now)
        }
        return createDocument(
            version = updated,
            revision = null,
            xml = document.xml,
            score = document.score,
            redoRevisionId = session.revision.id,
        )
    }

    private suspend fun createDocument(
        version: PracticeVersion,
        revision: PracticeVersionRevision?,
        xml: String,
        score: ScoreIr,
        redoRevisionId: String? = null,
        recoveryMessage: String? = null,
    ): PracticeVersionDocument {
        val detectedRedo = redoRevisionId ?: dao.latestChild(version.id, revision?.id)?.id
        return PracticeVersionDocument(
            version = version,
            document = MusicXmlDocument(version.title, xml, score, score.toSummary()),
            plan = PracticeEditPlan.fromJson(version.planJson),
            differences = PracticeVersionCompiler.differencesFromJson(version.differenceJson),
            revision = revision,
            revisions = dao.revisionsNewestFirst(version.id),
            redoRevisionId = detectedRedo,
            recoveryMessage = recoveryMessage,
        )
    }

    private suspend fun PracticeVersion.toDocument(xml: String, score: ScoreIr): PracticeVersionDocument =
        createDocument(this, null, xml, score)

    private fun readBase(version: PracticeVersion): MusicXmlDocument {
        val file = resolveRelativePath(version.musicXmlRelativePath)
        require(file.isFile) { "练习版本基础候选文件不存在" }
        return readDocument(file, version.title)
    }

    private fun readRevision(revision: PracticeVersionRevision, sourceName: String): MusicXmlDocument {
        val file = resolveRelativePath(revision.musicXmlRelativePath)
        require(file.isFile) { "练习版本修订文件不存在：${revision.revisionNumber}" }
        return readDocument(file, sourceName)
    }

    private fun readDocument(file: File, sourceName: String): MusicXmlDocument {
        val xml = MusicXmlCompatibilityNormalizer.normalize(file.readText(Charsets.UTF_8))
        val score = MusicXmlScoreParser.parse(xml)
        ScoreIrValidator.requireValid(score)
        return MusicXmlDocument(sourceName, xml, score, score.toSummary())
    }

    private fun writeNewFile(relativePath: String, xml: String): File {
        ScoreIrValidator.requireValid(MusicXmlScoreParser.parse(xml))
        val target = resolveRelativePath(relativePath)
        require(!target.exists()) { "练习版本文件已存在" }
        target.parentFile?.mkdirs()
        val temporary = File(target.parentFile, ".${target.name}.${UUID.randomUUID()}.tmp")
        try {
            FileOutputStream(temporary).use { output ->
                output.write(xml.toByteArray(Charsets.UTF_8))
                output.fd.sync()
            }
            check(temporary.renameTo(target)) { "无法提交练习版本文件" }
            return target
        } finally {
            temporary.delete()
        }
    }

    private fun resolveRelativePath(relativePath: String): File {
        require(relativePath.isNotBlank() && !File(relativePath).isAbsolute) { "无效的练习版本路径" }
        val root = appContext.filesDir.canonicalFile
        val resolved = File(root, relativePath).canonicalFile
        require(resolved.path.startsWith(root.path + File.separator)) { "练习版本路径越界" }
        return resolved
    }

    private fun revisionPath(structureId: String, practiceVersionId: String, revisionId: String): String =
        "structures/$structureId/practice-versions/$practiceVersionId/revisions/$revisionId.musicxml"
}
