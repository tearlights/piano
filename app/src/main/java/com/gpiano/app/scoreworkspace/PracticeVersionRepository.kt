package com.gpiano.app.scoreworkspace

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import com.gpiano.app.data.GpianoDatabase
import com.gpiano.app.data.GpianoDatabaseProvider
import com.gpiano.app.data.PracticeVersion
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
)

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
        val file = resolveRelativePath(version.musicXmlRelativePath)
        require(file.isFile) { "练习版本文件不存在" }
        val xml = file.readText(Charsets.UTF_8)
        val score = MusicXmlScoreParser.parse(xml)
        ScoreIrValidator.requireValid(score)
        version.toDocument(xml, score)
    }

    suspend fun setStatus(id: String, status: String): PracticeVersionDocument = withContext(Dispatchers.IO) {
        require(status in PracticeVersionStatus.all) { "练习版本状态无效" }
        requireNotNull(dao.find(id)) { "练习版本不存在" }
        dao.setStatus(id, status, System.currentTimeMillis())
        load(id)
    }

    suspend fun export(id: String, destination: Uri) = withContext(Dispatchers.IO) {
        val version = dao.find(id) ?: error("练习版本不存在")
        val source = resolveRelativePath(version.musicXmlRelativePath)
        require(source.isFile) { "练习版本文件不存在" }
        appContext.contentResolver.openOutputStream(destination)?.use { output ->
            source.inputStream().buffered().use { it.copyTo(output) }
        } ?: error("无法写入 MusicXML")
    }

    private fun PracticeVersion.toDocument(xml: String, score: ScoreIr): PracticeVersionDocument =
        PracticeVersionDocument(
            version = this,
            document = MusicXmlDocument(title, xml, score, score.toSummary()),
            plan = PracticeEditPlan.fromJson(planJson),
            differences = PracticeVersionCompiler.differencesFromJson(differenceJson),
        )

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
}
