package com.gpiano.app.data

import android.content.Context
import android.net.Uri
import com.gpiano.app.scoreworkspace.MusicXmlScoreParser
import com.gpiano.app.scoreworkspace.PracticeEditPlan
import com.gpiano.app.scoreworkspace.PracticeVersionCompiler
import com.gpiano.app.scoreworkspace.PlaybackHand
import com.gpiano.app.scoreworkspace.ScoreIrValidator
import com.gpiano.app.midi.PerformanceReportJson
import java.io.Closeable
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import org.json.JSONArray
import org.json.JSONObject

data class GpianoBackupSnapshot(
    val scores: List<Score>,
    val pages: List<ScorePage>,
    val bookmarks: List<Bookmark>,
    val folders: List<Folder>,
    val structures: List<ScoreStructure>,
    val revisions: List<ScoreRevision>,
    val recognitionJobs: List<RecognitionJob>,
    val practiceVersions: List<PracticeVersion>,
    val practiceAttempts: List<PracticeAttempt>,
    val midiPerformanceEvents: List<MidiPerformanceEvent>,
    val practiceVersionRevisions: List<PracticeVersionRevision> = emptyList(),
)

data class StagedGpianoBackup(
    val formatVersion: Int,
    val snapshot: GpianoBackupSnapshot,
    val directory: File,
    val dataPaths: Set<String>,
) : Closeable {
    override fun close() {
        directory.deleteRecursively()
    }
}

class GpianoBackupManager(private val context: Context) {
    fun export(snapshot: GpianoBackupSnapshot, destination: Uri) {
        validateSnapshot(snapshot, context.filesDir)
        val paths = referencedPaths(snapshot)
        val fileMetadata = paths.associateWith { relativePath ->
            val file = resolveInside(context.filesDir, relativePath)
            require(file.isFile) { "备份所需文件不存在：$relativePath" }
            BackupFileMetadata(relativePath, file.length(), file.sha256())
        }
        val library = snapshot.toLibraryJson()
        val libraryBytes = library.toString().toByteArray(Charsets.UTF_8)
        requireBackupJsonSize("library.json", libraryBytes.size.toLong(), MAX_JSON_BYTES)
        val manifest = JSONObject()
            .put("formatVersion", CURRENT_FORMAT_VERSION)
            .put("createdAt", System.currentTimeMillis())
            .put(
                "files",
                JSONArray().apply {
                    fileMetadata.values.forEach { item ->
                        put(
                            JSONObject()
                                .put("path", item.path)
                                .put("size", item.size)
                                .put("sha256", item.sha256),
                        )
                    }
                },
            )
        val manifestBytes = manifest.toString().toByteArray(Charsets.UTF_8)
        requireBackupJsonSize("manifest.json", manifestBytes.size.toLong(), MAX_JSON_BYTES)

        context.contentResolver.openOutputStream(destination)?.use { output ->
            ZipOutputStream(output.buffered()).use { zip ->
                fileMetadata.values.forEach { item ->
                    zip.putNextEntry(ZipEntry(item.path))
                    resolveInside(context.filesDir, item.path).inputStream().buffered().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
                zip.writeBytesEntry("library.json", libraryBytes)
                zip.writeBytesEntry("manifest.json", manifestBytes)
            }
        } ?: error("无法写入备份文件")
    }

    fun stage(source: Uri): StagedGpianoBackup {
        val staging = File(context.cacheDir, "backup-restore/${UUID.randomUUID()}")
        check(staging.mkdirs()) { "无法创建备份校验目录" }
        try {
            val seen = hashSetOf<String>()
            var totalBytes = 0L
            context.contentResolver.openInputStream(source)?.use { input ->
                ZipInputStream(input.buffered()).use { zip ->
                    var entry = zip.nextEntry
                    while (entry != null) {
                        val rawName = if (entry.isDirectory) entry.name.trimEnd('/') else entry.name
                        require(rawName.isNotBlank()) { "备份包包含无效目录条目" }
                        val name = safeArchivePath(rawName)
                        require(seen.add(name)) { "备份包包含重复条目：$name" }
                        if (!entry.isDirectory) {
                            val target = resolveInside(staging, name)
                            target.parentFile?.mkdirs()
                            target.outputStream().buffered().use { output ->
                                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                                var entryBytes = 0L
                                while (true) {
                                    val read = zip.read(buffer)
                                    if (read < 0) break
                                    entryBytes += read
                                    totalBytes += read
                                    require(entryBytes <= MAX_ENTRY_BYTES) { "备份条目过大：$name" }
                                    require(totalBytes <= MAX_TOTAL_BYTES) { "备份包解压后过大" }
                                    output.write(buffer, 0, read)
                                }
                            }
                        }
                        zip.closeEntry()
                        entry = zip.nextEntry
                    }
                }
            } ?: error("无法读取备份文件")

            val libraryFile = File(staging, "library.json")
            require(libraryFile.isFile) { "备份包缺少 library.json" }
            require(libraryFile.length() <= MAX_JSON_BYTES) { "library.json 过大" }
            val library = JSONObject(libraryFile.readText(Charsets.UTF_8))
            val version = library.optInt("formatVersion", 1)
            require(version in 1..CURRENT_FORMAT_VERSION) {
                if (version > CURRENT_FORMAT_VERSION) "备份版本 $version 高于当前支持版本" else "不支持的备份版本：$version"
            }
            val snapshot = when (version) {
                1 -> parseVersion1(library)
                2 -> parseVersion2(library)
                3 -> parseVersion3(library)
                4 -> parseVersion4(library)
                5 -> parseVersion5(library)
                else -> parseVersion6(library)
            }
            if (version >= 2) validateManifest(staging, version)
            validateSnapshot(snapshot, staging)
            return StagedGpianoBackup(version, snapshot, staging, referencedPaths(snapshot))
        } catch (error: Throwable) {
            staging.deleteRecursively()
            throw error
        }
    }

    fun validate(source: Uri): Boolean = runCatching { stage(source).use { } }.isSuccess

    private fun validateManifest(staging: File, expectedVersion: Int) {
        val manifestFile = File(staging, "manifest.json")
        require(manifestFile.isFile) { "备份包缺少 manifest.json" }
        require(manifestFile.length() <= MAX_JSON_BYTES) { "manifest.json 过大" }
        val manifest = JSONObject(manifestFile.readText(Charsets.UTF_8))
        require(manifest.getInt("formatVersion") == expectedVersion) { "备份清单版本不一致" }
        val declared = linkedMapOf<String, BackupFileMetadata>()
        val files = manifest.getJSONArray("files")
        repeat(files.length()) { index ->
            val item = files.getJSONObject(index)
            val path = safeDataPath(item.getString("path"))
            require(declared.put(path, BackupFileMetadata(path, item.getLong("size"), item.getString("sha256"))) == null) {
                "备份清单包含重复文件：$path"
            }
        }
        declared.values.forEach { expected ->
            val file = resolveInside(staging, expected.path)
            require(file.isFile) { "备份文件缺失：${expected.path}" }
            require(file.length() == expected.size) { "备份文件大小不匹配：${expected.path}" }
            require(file.sha256().equals(expected.sha256, ignoreCase = true)) { "备份文件校验失败：${expected.path}" }
        }
        val actualDataFiles = staging.walkTopDown()
            .filter(File::isFile)
            .map { it.relativeTo(staging).invariantSeparatorsPath }
            .filter { it != "library.json" && it != "manifest.json" }
            .toSet()
        require(actualDataFiles == declared.keys) { "备份清单与实际文件不一致" }
    }

    private fun validateSnapshot(snapshot: GpianoBackupSnapshot, fileRoot: File) {
        requireUnique(snapshot.scores.map(Score::id), "曲谱")
        requireUnique(snapshot.folders.map(Folder::id), "文件夹")
        requireUnique(snapshot.pages.map { "${it.scoreId}:${it.sourceIndex}" }, "页面")
        requireUnique(snapshot.bookmarks.map { "${it.scoreId}:${it.page}" }, "书签")
        requireUnique(snapshot.structures.map(ScoreStructure::id), "结构化乐谱")
        requireUnique(snapshot.structures.map(ScoreStructure::sourceKey), "结构化来源")
        requireUnique(snapshot.revisions.map(ScoreRevision::id), "修订")
        requireUnique(snapshot.revisions.map { "${it.structureId}:${it.revisionNumber}" }, "修订号")
        requireUnique(snapshot.recognitionJobs.map(RecognitionJob::id), "识别任务")
        requireUnique(snapshot.practiceVersions.map(PracticeVersion::id), "练习版本")
        requireUnique(snapshot.practiceVersions.map(PracticeVersion::musicXmlRelativePath), "练习版本文件")
        requireUnique(snapshot.practiceVersionRevisions.map(PracticeVersionRevision::id), "练习版本修订")
        requireUnique(snapshot.practiceVersionRevisions.map { "${it.practiceVersionId}:${it.revisionNumber}" }, "练习版本修订号")
        requireUnique(snapshot.practiceVersionRevisions.map(PracticeVersionRevision::musicXmlRelativePath), "练习版本修订文件")
        requireUnique(snapshot.practiceAttempts.map(PracticeAttempt::id), "跟弹记录")
        requireUnique(snapshot.midiPerformanceEvents.map { "${it.attemptId}:${it.sequence}" }, "MIDI 按键")

        val scoreIds = snapshot.scores.mapTo(hashSetOf(), Score::id)
        val folderIds = snapshot.folders.mapTo(hashSetOf(), Folder::id)
        snapshot.scores.forEach { score ->
            require(score.id.isNotBlank() && score.title.isNotBlank()) { "曲谱元数据不完整" }
            require(score.folderId == null || score.folderId in folderIds) { "曲谱引用了不存在的文件夹" }
        }
        snapshot.pages.forEach { require(it.scoreId in scoreIds) { "页面引用了不存在的曲谱" } }
        snapshot.bookmarks.forEach { require(it.scoreId in scoreIds) { "书签引用了不存在的曲谱" } }

        val structuresById = snapshot.structures.associateBy(ScoreStructure::id)
        val revisionsById = snapshot.revisions.associateBy(ScoreRevision::id)
        snapshot.structures.forEach { structure ->
            require(structure.sourceScoreId == null || structure.sourceScoreId in scoreIds) {
                "结构化乐谱引用了不存在的原谱"
            }
            val current = revisionsById[structure.currentRevisionId]
            require(current?.structureId == structure.id) { "结构化乐谱的当前修订无效" }
        }
        snapshot.revisions.forEach { revision ->
            require(revision.structureId in structuresById) { "修订引用了不存在的结构化乐谱" }
            revision.parentRevisionId?.let { parentId ->
                require(revisionsById[parentId]?.structureId == revision.structureId) { "修订的父版本无效" }
            }
        }
        snapshot.recognitionJobs.forEach { job ->
            require(job.sourceScoreId in scoreIds) { "识别任务引用了不存在的原谱" }
            require(job.sourcePageIndex >= 0) { "识别任务页码无效" }
            require(job.inputSha256.matches(Regex("[0-9a-fA-F]{64}"))) { "识别任务输入摘要无效" }
            require(job.status in RecognitionJobStatus.all) { "识别任务状态无效" }
            job.resultStructureId?.let { structureId ->
                require(structureId in structuresById) { "识别任务引用了不存在的结构化乐谱" }
            }
            if (job.status == RecognitionJobStatus.Ready) {
                require(job.resultStructureId != null) { "已完成识别任务缺少结构化结果" }
            }
        }
        val practiceVersionsById = snapshot.practiceVersions.associateBy(PracticeVersion::id)
        val practiceVersionRevisionsById = snapshot.practiceVersionRevisions.associateBy(PracticeVersionRevision::id)
        snapshot.practiceVersions.forEach { version ->
            require(version.structureId in structuresById) { "练习版本引用了不存在的结构化乐谱" }
            require(revisionsById[version.baseRevisionId]?.structureId == version.structureId) {
                "练习版本的基础修订无效"
            }
            require(version.status in PracticeVersionStatus.all) { "练习版本状态无效" }
            require(version.fromMeasure >= 1 && version.toMeasure >= version.fromMeasure) { "练习版本范围无效" }
            val plan = PracticeEditPlan.fromJson(version.planJson)
            require(plan.fromMeasure == version.fromMeasure && plan.toMeasure == version.toMeasure) {
                "练习版本计划范围不一致"
            }
            PracticeVersionCompiler.differencesFromJson(version.differenceJson)
            version.currentRevisionId?.let { revisionId ->
                require(practiceVersionRevisionsById[revisionId]?.practiceVersionId == version.id) {
                    "练习版本的当前修订无效"
                }
            }
        }
        snapshot.practiceVersionRevisions.forEach { revision ->
            require(revision.practiceVersionId in practiceVersionsById) { "练习版本修订引用了不存在的练习版本" }
            require(revision.revisionNumber >= 1) { "练习版本修订号无效" }
            revision.parentRevisionId?.let { parentId ->
                val parent = practiceVersionRevisionsById[parentId]
                require(parent?.practiceVersionId == revision.practiceVersionId) { "练习版本修订的父版本无效" }
                require(parent.revisionNumber < revision.revisionNumber) { "练习版本修订顺序无效" }
            }
            revision.operationJson?.let(::JSONObject)
        }
        val attemptsById = snapshot.practiceAttempts.associateBy(PracticeAttempt::id)
        snapshot.practiceAttempts.forEach { attempt ->
            require(attempt.structureId in structuresById) { "跟弹记录引用了不存在的结构化乐谱" }
            require(revisionsById[attempt.sourceRevisionId]?.structureId == attempt.structureId) {
                "跟弹记录的来源修订无效"
            }
            attempt.practiceVersionId?.let { versionId ->
                val version = practiceVersionsById[versionId]
                require(version?.structureId == attempt.structureId && version.baseRevisionId == attempt.sourceRevisionId) {
                    "跟弹记录的练习版本无效"
                }
            }
            require(attempt.fromMeasure >= 1 && attempt.toMeasure >= attempt.fromMeasure) { "跟弹范围无效" }
            require(attempt.hand in PlaybackHand.entries.map(PlaybackHand::name)) { "跟弹手别无效" }
            require(attempt.speed in 0.25..2.0) { "跟弹速度无效" }
            require(attempt.inputKind in PracticeAttemptInputKind.all) { "跟弹输入类型无效" }
            require(attempt.deviceName.isNotBlank()) { "跟弹设备名称为空" }
            require(attempt.startedAt > 0 && attempt.finishedAt >= attempt.startedAt) { "跟弹时间无效" }
            val report = PerformanceReportJson.decode(attempt.resultJson)
            require(
                attempt.expectedCount == report.expectedCount &&
                    attempt.playedCount == report.playedCount &&
                    attempt.pitchMatchedCount == report.pitchMatchedCount &&
                    attempt.rhythmMatchedCount == report.rhythmMatchedCount &&
                    attempt.missedCount == report.missedCount &&
                    attempt.extraCount == report.extraCount &&
                    attempt.wrongPitchCount == report.wrongPitchCount &&
                    attempt.continuityPercent == report.continuityPercent &&
                    attempt.pitchAccuracyPercent == report.pitchAccuracyPercent &&
                    attempt.rhythmAccuracyPercent == report.rhythmAccuracyPercent,
            ) { "跟弹汇总与结果明细不一致" }
        }
        snapshot.midiPerformanceEvents.forEach { event ->
            require(event.attemptId in attemptsById) { "MIDI 按键引用了不存在的跟弹记录" }
            require(event.sequence >= 0 && event.channel in 0..15) { "MIDI 按键序号或通道无效" }
            require(event.midiPitch in 0..127 && event.velocity in 0..127) { "MIDI 按键音高或力度无效" }
            require(event.onsetNanos >= 0 && (event.durationNanos == null || event.durationNanos >= 0)) {
                "MIDI 按键时间无效"
            }
        }
        val eventsByAttempt = snapshot.midiPerformanceEvents.groupBy(MidiPerformanceEvent::attemptId)
        snapshot.practiceAttempts.forEach { attempt ->
            require(eventsByAttempt[attempt.id].orEmpty().size == attempt.playedCount) { "跟弹原始按键数不一致" }
        }

        referencedPaths(snapshot).forEach { path ->
            val file = resolveInside(fileRoot, path)
            require(file.isFile) { "备份引用的文件不存在：$path" }
        }
        snapshot.revisions.forEach { revision ->
            val xml = resolveInside(fileRoot, revision.musicXmlRelativePath).readText(Charsets.UTF_8)
            ScoreIrValidator.requireValid(MusicXmlScoreParser.parse(xml))
        }
        snapshot.practiceVersions.forEach { version ->
            val xml = resolveInside(fileRoot, version.musicXmlRelativePath).readText(Charsets.UTF_8)
            ScoreIrValidator.requireValid(MusicXmlScoreParser.parse(xml))
        }
        snapshot.practiceVersionRevisions.forEach { revision ->
            val xml = resolveInside(fileRoot, revision.musicXmlRelativePath).readText(Charsets.UTF_8)
            ScoreIrValidator.requireValid(MusicXmlScoreParser.parse(xml))
        }
    }

    private fun referencedPaths(snapshot: GpianoBackupSnapshot): Set<String> = linkedSetOf<String>().apply {
        snapshot.scores.map(Score::relativePath).filter(String::isNotBlank).forEach { add(safeDataPath(it)) }
        snapshot.pages.mapNotNull(ScorePage::relativePath).forEach { add(safeDataPath(it)) }
        snapshot.structures.mapNotNull(ScoreStructure::sourceMapRelativePath).forEach { add(safeDataPath(it)) }
        snapshot.revisions.map(ScoreRevision::musicXmlRelativePath).forEach { add(safeDataPath(it)) }
        snapshot.practiceVersions.map(PracticeVersion::musicXmlRelativePath).forEach { add(safeDataPath(it)) }
        snapshot.practiceVersionRevisions.map(PracticeVersionRevision::musicXmlRelativePath).forEach { add(safeDataPath(it)) }
    }

    private fun parseVersion1(library: JSONObject): GpianoBackupSnapshot {
        val scores = library.getJSONArray("scores").mapObjects { item ->
            Score(
                id = item.getString("id"),
                title = item.getString("title"),
                fileName = item.getString("fileName"),
                relativePath = item.getString("relativePath"),
                mimeType = item.getString("mimeType"),
                importedAt = item.getLong("importedAt"),
                isFavorite = item.optBoolean("isFavorite"),
            )
        }
        return GpianoBackupSnapshot(
            scores,
            emptyList(),
            emptyList(),
            emptyList(),
            emptyList(),
            emptyList(),
            emptyList(),
            emptyList(),
            emptyList(),
            emptyList(),
        )
    }

    private fun parseVersion2(library: JSONObject): GpianoBackupSnapshot = GpianoBackupSnapshot(
        scores = library.arrayOrEmpty("scores").mapObjects { item ->
            Score(
                id = item.getString("id"),
                title = item.getString("title"),
                fileName = item.getString("fileName"),
                relativePath = item.getString("relativePath"),
                mimeType = item.getString("mimeType"),
                importedAt = item.getLong("importedAt"),
                isFavorite = item.optBoolean("isFavorite"),
                folderId = item.nullableString("folderId"),
                lastOpenedAt = item.nullableLong("lastOpenedAt"),
            )
        },
        pages = library.arrayOrEmpty("pages").mapObjects { item ->
            ScorePage(
                scoreId = item.getString("scoreId"),
                sourceIndex = item.getInt("sourceIndex"),
                displayIndex = item.getInt("displayIndex"),
                relativePath = item.nullableString("relativePath"),
            )
        },
        bookmarks = library.arrayOrEmpty("bookmarks").mapObjects { item ->
            Bookmark(item.getString("scoreId"), item.getInt("page"), item.getLong("createdAt"))
        },
        folders = library.arrayOrEmpty("folders").mapObjects { item ->
            Folder(item.getString("id"), item.getString("name"), item.getLong("createdAt"))
        },
        structures = library.arrayOrEmpty("structures").mapObjects { item ->
            ScoreStructure(
                id = item.getString("id"),
                sourceScoreId = item.nullableString("sourceScoreId"),
                sourceKey = item.getString("sourceKey"),
                title = item.getString("title"),
                formatVersion = item.getInt("formatVersion"),
                currentRevisionId = item.getString("currentRevisionId"),
                recognitionStatus = item.getString("recognitionStatus"),
                recognitionConfidence = item.nullableDouble("recognitionConfidence"),
                sourceMapRelativePath = item.nullableString("sourceMapRelativePath"),
                createdAt = item.getLong("createdAt"),
                updatedAt = item.getLong("updatedAt"),
            )
        },
        revisions = library.arrayOrEmpty("revisions").mapObjects { item ->
            ScoreRevision(
                id = item.getString("id"),
                structureId = item.getString("structureId"),
                parentRevisionId = item.nullableString("parentRevisionId"),
                revisionNumber = item.getInt("revisionNumber"),
                kind = item.getString("kind"),
                musicXmlRelativePath = item.getString("musicXmlRelativePath"),
                operationJson = item.nullableString("operationJson"),
                createdAt = item.getLong("createdAt"),
            )
        },
        recognitionJobs = emptyList(),
        practiceVersions = emptyList(),
        practiceAttempts = emptyList(),
        midiPerformanceEvents = emptyList(),
    )

    private fun parseVersion3(library: JSONObject): GpianoBackupSnapshot {
        val base = parseVersion2(library)
        return base.copy(
            recognitionJobs = library.arrayOrEmpty("recognitionJobs").mapObjects { item ->
                RecognitionJob(
                    id = item.getString("id"),
                    sourceScoreId = item.getString("sourceScoreId"),
                    sourcePageIndex = item.getInt("sourcePageIndex"),
                    provider = item.getString("provider"),
                    remoteJobId = null,
                    inputSha256 = item.getString("inputSha256"),
                    status = item.getString("status"),
                    stage = item.getString("stage"),
                    attempt = item.getInt("attempt"),
                    resultStructureId = item.nullableString("resultStructureId"),
                    errorCode = item.nullableString("errorCode"),
                    errorMessage = item.nullableString("errorMessage"),
                    diagnosticsJson = item.nullableString("diagnosticsJson"),
                    createdAt = item.getLong("createdAt"),
                    updatedAt = item.getLong("updatedAt"),
                ).restoredCopy()
            },
        )
    }

    private fun parseVersion4(library: JSONObject): GpianoBackupSnapshot {
        val base = parseVersion3(library)
        return base.copy(
            practiceVersions = library.arrayOrEmpty("practiceVersions").mapObjects { item ->
                PracticeVersion(
                    id = item.getString("id"),
                    structureId = item.getString("structureId"),
                    baseRevisionId = item.getString("baseRevisionId"),
                    title = item.getString("title"),
                    purpose = item.getString("purpose"),
                    status = item.getString("status"),
                    fromMeasure = item.getInt("fromMeasure"),
                    toMeasure = item.getInt("toMeasure"),
                    musicXmlRelativePath = item.getString("musicXmlRelativePath"),
                    currentRevisionId = null,
                    planJson = item.getString("planJson"),
                    differenceJson = item.getString("differenceJson"),
                    createdAt = item.getLong("createdAt"),
                    updatedAt = item.getLong("updatedAt"),
                )
            },
        )
    }

    private fun parseVersion5(library: JSONObject): GpianoBackupSnapshot {
        val base = parseVersion4(library)
        return base.copy(
            practiceAttempts = library.arrayOrEmpty("practiceAttempts").mapObjects { item ->
                PracticeAttempt(
                    id = item.getString("id"),
                    structureId = item.getString("structureId"),
                    sourceRevisionId = item.getString("sourceRevisionId"),
                    practiceVersionId = item.nullableString("practiceVersionId"),
                    fromMeasure = item.getInt("fromMeasure"),
                    toMeasure = item.getInt("toMeasure"),
                    hand = item.getString("hand"),
                    speed = item.getDouble("speed"),
                    inputKind = item.getString("inputKind"),
                    deviceId = item.nullableInt("deviceId"),
                    deviceName = item.getString("deviceName"),
                    startedAt = item.getLong("startedAt"),
                    finishedAt = item.getLong("finishedAt"),
                    expectedCount = item.getInt("expectedCount"),
                    playedCount = item.getInt("playedCount"),
                    pitchMatchedCount = item.getInt("pitchMatchedCount"),
                    rhythmMatchedCount = item.getInt("rhythmMatchedCount"),
                    missedCount = item.getInt("missedCount"),
                    extraCount = item.getInt("extraCount"),
                    wrongPitchCount = item.getInt("wrongPitchCount"),
                    continuityPercent = item.getInt("continuityPercent"),
                    pitchAccuracyPercent = item.getInt("pitchAccuracyPercent"),
                    rhythmAccuracyPercent = item.getInt("rhythmAccuracyPercent"),
                    resultJson = item.getString("resultJson"),
                )
            },
            midiPerformanceEvents = library.arrayOrEmpty("midiPerformanceEvents").mapObjects { item ->
                MidiPerformanceEvent(
                    attemptId = item.getString("attemptId"),
                    sequence = item.getInt("sequence"),
                    channel = item.getInt("channel"),
                    midiPitch = item.getInt("midiPitch"),
                    velocity = item.getInt("velocity"),
                    onsetNanos = item.getLong("onsetNanos"),
                    durationNanos = item.nullableLong("durationNanos"),
                )
            },
        )
    }

    private fun parseVersion6(library: JSONObject): GpianoBackupSnapshot {
        val base = parseVersion5(library)
        val currentRevisionByVersionId = library.arrayOrEmpty("practiceVersions")
            .mapObjects { item -> item.getString("id") to item.nullableString("currentRevisionId") }
            .toMap()
        return base.copy(
            practiceVersions = base.practiceVersions.map { version ->
                version.copy(currentRevisionId = currentRevisionByVersionId[version.id])
            },
            practiceVersionRevisions = library.arrayOrEmpty("practiceVersionRevisions").mapObjects { item ->
                PracticeVersionRevision(
                    id = item.getString("id"),
                    practiceVersionId = item.getString("practiceVersionId"),
                    parentRevisionId = item.nullableString("parentRevisionId"),
                    revisionNumber = item.getInt("revisionNumber"),
                    kind = item.getString("kind"),
                    musicXmlRelativePath = item.getString("musicXmlRelativePath"),
                    operationJson = item.nullableString("operationJson"),
                    createdAt = item.getLong("createdAt"),
                )
            },
        )
    }

    private data class BackupFileMetadata(val path: String, val size: Long, val sha256: String)

    companion object {
        const val CURRENT_FORMAT_VERSION = 6
        internal const val MAX_JSON_BYTES = 64L * 1024 * 1024
        private const val MAX_ENTRY_BYTES = 256L * 1024 * 1024
        private const val MAX_TOTAL_BYTES = 1024L * 1024 * 1024
    }
}

private fun GpianoBackupSnapshot.toLibraryJson(): JSONObject = JSONObject()
    .put("formatVersion", GpianoBackupManager.CURRENT_FORMAT_VERSION)
    .put("scores", JSONArray().apply { scores.forEach { put(it.toJson()) } })
    .put("pages", JSONArray().apply { pages.forEach { put(it.toJson()) } })
    .put("bookmarks", JSONArray().apply { bookmarks.forEach { put(it.toJson()) } })
    .put("folders", JSONArray().apply { folders.forEach { put(it.toJson()) } })
    .put("structures", JSONArray().apply { structures.forEach { put(it.toJson()) } })
    .put("revisions", JSONArray().apply { revisions.forEach { put(it.toJson()) } })
    .put("recognitionJobs", JSONArray().apply { recognitionJobs.forEach { put(it.exportCopy().toJson()) } })
    .put("practiceVersions", JSONArray().apply { practiceVersions.forEach { put(it.toJson()) } })
    .put("practiceVersionRevisions", JSONArray().apply { practiceVersionRevisions.forEach { put(it.toJson()) } })
    .put("practiceAttempts", JSONArray().apply { practiceAttempts.forEach { put(it.toJson()) } })
    .put("midiPerformanceEvents", JSONArray().apply { midiPerformanceEvents.forEach { put(it.toJson()) } })

private fun Score.toJson() = JSONObject()
    .put("id", id).put("title", title).put("fileName", fileName).put("relativePath", relativePath)
    .put("mimeType", mimeType).put("importedAt", importedAt).put("isFavorite", isFavorite)
    .putNullable("folderId", folderId).putNullable("lastOpenedAt", lastOpenedAt)

private fun ScorePage.toJson() = JSONObject()
    .put("scoreId", scoreId).put("sourceIndex", sourceIndex).put("displayIndex", displayIndex)
    .putNullable("relativePath", relativePath)

private fun Bookmark.toJson() = JSONObject()
    .put("scoreId", scoreId).put("page", page).put("createdAt", createdAt)

private fun Folder.toJson() = JSONObject().put("id", id).put("name", name).put("createdAt", createdAt)

private fun ScoreStructure.toJson() = JSONObject()
    .put("id", id).putNullable("sourceScoreId", sourceScoreId).put("sourceKey", sourceKey)
    .put("title", title).put("formatVersion", formatVersion).put("currentRevisionId", currentRevisionId)
    .put("recognitionStatus", recognitionStatus).putNullable("recognitionConfidence", recognitionConfidence)
    .putNullable("sourceMapRelativePath", sourceMapRelativePath).put("createdAt", createdAt).put("updatedAt", updatedAt)

private fun ScoreRevision.toJson() = JSONObject()
    .put("id", id).put("structureId", structureId).putNullable("parentRevisionId", parentRevisionId)
    .put("revisionNumber", revisionNumber).put("kind", kind).put("musicXmlRelativePath", musicXmlRelativePath)
    .putNullable("operationJson", operationJson).put("createdAt", createdAt)

private fun RecognitionJob.toJson() = JSONObject()
    .put("id", id).put("sourceScoreId", sourceScoreId).put("sourcePageIndex", sourcePageIndex)
    .put("provider", provider).put("inputSha256", inputSha256).put("status", status).put("stage", stage)
    .put("attempt", attempt).putNullable("resultStructureId", resultStructureId)
    .putNullable("errorCode", errorCode).putNullable("errorMessage", errorMessage)
    .putNullable("diagnosticsJson", diagnosticsJson).put("createdAt", createdAt).put("updatedAt", updatedAt)

private fun PracticeVersion.toJson() = JSONObject()
    .put("id", id).put("structureId", structureId).put("baseRevisionId", baseRevisionId)
    .put("title", title).put("purpose", purpose).put("status", status)
    .put("fromMeasure", fromMeasure).put("toMeasure", toMeasure)
    .put("musicXmlRelativePath", musicXmlRelativePath).putNullable("currentRevisionId", currentRevisionId)
    .put("planJson", planJson)
    .put("differenceJson", differenceJson).put("createdAt", createdAt).put("updatedAt", updatedAt)

private fun PracticeVersionRevision.toJson() = JSONObject()
    .put("id", id).put("practiceVersionId", practiceVersionId).putNullable("parentRevisionId", parentRevisionId)
    .put("revisionNumber", revisionNumber).put("kind", kind).put("musicXmlRelativePath", musicXmlRelativePath)
    .putNullable("operationJson", operationJson).put("createdAt", createdAt)

private fun PracticeAttempt.toJson() = JSONObject()
    .put("id", id).put("structureId", structureId).put("sourceRevisionId", sourceRevisionId)
    .putNullable("practiceVersionId", practiceVersionId)
    .put("fromMeasure", fromMeasure).put("toMeasure", toMeasure).put("hand", hand).put("speed", speed)
    .put("inputKind", inputKind).putNullable("deviceId", deviceId).put("deviceName", deviceName)
    .put("startedAt", startedAt).put("finishedAt", finishedAt)
    .put("expectedCount", expectedCount).put("playedCount", playedCount)
    .put("pitchMatchedCount", pitchMatchedCount).put("rhythmMatchedCount", rhythmMatchedCount)
    .put("missedCount", missedCount).put("extraCount", extraCount).put("wrongPitchCount", wrongPitchCount)
    .put("continuityPercent", continuityPercent).put("pitchAccuracyPercent", pitchAccuracyPercent)
    .put("rhythmAccuracyPercent", rhythmAccuracyPercent).put("resultJson", resultJson)

private fun MidiPerformanceEvent.toJson() = JSONObject()
    .put("attemptId", attemptId).put("sequence", sequence).put("channel", channel)
    .put("midiPitch", midiPitch).put("velocity", velocity).put("onsetNanos", onsetNanos)
    .putNullable("durationNanos", durationNanos)

private fun RecognitionJob.exportCopy(): RecognitionJob = copy(remoteJobId = null)

private fun RecognitionJob.restoredCopy(): RecognitionJob = when {
    status == RecognitionJobStatus.Ready && resultStructureId != null -> copy(remoteJobId = null)
    status == RecognitionJobStatus.Failed || status == RecognitionJobStatus.Cancelled -> copy(remoteJobId = null)
    else -> copy(
        remoteJobId = null,
        status = RecognitionJobStatus.Failed,
        stage = "restore",
        errorCode = "restart_required",
        errorMessage = "恢复后的识别任务需要重新转换",
    )
}

private fun JSONObject.putNullable(key: String, value: Any?): JSONObject = put(key, value ?: JSONObject.NULL)
private fun JSONObject.nullableString(key: String): String? = if (!has(key) || isNull(key)) null else getString(key)
private fun JSONObject.nullableLong(key: String): Long? = if (!has(key) || isNull(key)) null else getLong(key)
private fun JSONObject.nullableDouble(key: String): Double? = if (!has(key) || isNull(key)) null else getDouble(key)
private fun JSONObject.nullableInt(key: String): Int? = if (!has(key) || isNull(key)) null else getInt(key)
private fun JSONObject.arrayOrEmpty(key: String): JSONArray = optJSONArray(key) ?: JSONArray()

private inline fun <T> JSONArray.mapObjects(transform: (JSONObject) -> T): List<T> =
    List(length()) { index -> transform(getJSONObject(index)) }

private fun ZipOutputStream.writeBytesEntry(name: String, value: ByteArray) {
    putNextEntry(ZipEntry(name))
    write(value)
    closeEntry()
}

internal fun requireBackupJsonSize(name: String, size: Long, limit: Long) {
    require(size <= limit) { "$name 过大（${size}B，最大 ${limit}B）" }
}

private fun safeArchivePath(path: String): String {
    require(path.isNotBlank() && !path.startsWith('/') && '\\' !in path && '\u0000' !in path) { "备份条目路径无效" }
    val segments = path.split('/')
    require(segments.none { it.isBlank() || it == "." || it == ".." }) { "备份条目路径越界：$path" }
    return path
}

private fun safeDataPath(path: String): String {
    val safe = safeArchivePath(path)
    require(safe.startsWith("scores/") || safe.startsWith("structures/")) { "备份数据路径不受支持：$safe" }
    return safe
}

private fun resolveInside(root: File, relativePath: String): File {
    val safe = safeArchivePath(relativePath)
    val canonicalRoot = root.canonicalFile
    val resolved = File(canonicalRoot, safe).canonicalFile
    require(resolved.path.startsWith(canonicalRoot.path + File.separator)) { "备份路径越界：$relativePath" }
    return resolved
}

private fun File.sha256(): String {
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

private fun requireUnique(values: List<String>, label: String) {
    require(values.none(String::isBlank) && values.size == values.toSet().size) { "$label 标识为空或重复" }
}
