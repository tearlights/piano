package com.gpiano.app.midi

import android.content.Context
import androidx.room.withTransaction
import com.gpiano.app.data.GpianoDatabase
import com.gpiano.app.data.GpianoDatabaseProvider
import com.gpiano.app.data.MidiPerformanceEvent
import com.gpiano.app.data.PracticeAttempt
import com.gpiano.app.data.PracticeAttemptInputKind
import com.gpiano.app.scoreworkspace.PlaybackPlan
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class StoredPracticeAttempt(
    val attempt: PracticeAttempt,
    val performed: List<PerformedMidiNote>,
    val report: PerformanceReport,
)

class PracticeAttemptRepository(
    context: Context,
    private val database: GpianoDatabase = GpianoDatabaseProvider.get(context),
) {
    private val dao = database.practiceAttemptDao()

    suspend fun save(
        structureId: String,
        sourceRevisionId: String,
        practiceVersionId: String?,
        plan: PlaybackPlan,
        inputKind: String,
        deviceId: Int?,
        deviceName: String,
        startedAt: Long,
        finishedAt: Long,
        performed: List<PerformedMidiNote>,
        report: PerformanceReport,
    ): StoredPracticeAttempt = withContext(Dispatchers.IO) {
        require(inputKind in PracticeAttemptInputKind.all) { "跟弹输入类型无效" }
        require(deviceName.isNotBlank()) { "跟弹输入名称不能为空" }
        require(startedAt > 0 && finishedAt >= startedAt) { "跟弹时间范围无效" }
        PerformanceReportJson.requireValid(report)
        require(report.playedCount == performed.size) { "跟弹按键数与反馈不一致" }
        require(plan.selection.startMeasure >= 1 && plan.selection.endMeasure >= plan.selection.startMeasure) {
            "跟弹小节范围无效"
        }

        val id = UUID.randomUUID().toString()
        val entity = PracticeAttempt(
            id = id,
            structureId = structureId,
            sourceRevisionId = sourceRevisionId,
            practiceVersionId = practiceVersionId,
            fromMeasure = plan.selection.startMeasure,
            toMeasure = plan.selection.endMeasure,
            hand = plan.selection.hand.name,
            speed = plan.selection.speed,
            inputKind = inputKind,
            deviceId = deviceId,
            deviceName = deviceName.trim(),
            startedAt = startedAt,
            finishedAt = finishedAt,
            expectedCount = report.expectedCount,
            playedCount = report.playedCount,
            pitchMatchedCount = report.pitchMatchedCount,
            rhythmMatchedCount = report.rhythmMatchedCount,
            missedCount = report.missedCount,
            extraCount = report.extraCount,
            wrongPitchCount = report.wrongPitchCount,
            continuityPercent = report.continuityPercent,
            pitchAccuracyPercent = report.pitchAccuracyPercent,
            rhythmAccuracyPercent = report.rhythmAccuracyPercent,
            resultJson = PerformanceReportJson.encode(report),
        )
        val eventEntities = performed.map { note ->
            MidiPerformanceEvent(
                attemptId = id,
                sequence = note.sequence,
                channel = note.channel,
                midiPitch = note.midiPitch,
                velocity = note.velocity,
                onsetNanos = note.onsetNanos,
                durationNanos = note.durationNanos,
            )
        }
        database.withTransaction {
            val structure = database.scoreStructureDao().findStructure(structureId)
                ?: error("结构化乐谱已被删除")
            val revision = database.scoreStructureDao().findRevision(sourceRevisionId)
                ?: error("跟弹来源修订不存在")
            require(revision.structureId == structure.id) { "跟弹来源修订不属于当前乐谱" }
            practiceVersionId?.let { versionId ->
                val version = database.practiceVersionDao().find(versionId) ?: error("跟弹练习版本不存在")
                require(version.structureId == structure.id && version.baseRevisionId == sourceRevisionId) {
                    "跟弹练习版本与来源修订不一致"
                }
            }
            dao.insertAttempt(entity)
            if (eventEntities.isNotEmpty()) dao.insertEvents(eventEntities)
        }
        StoredPracticeAttempt(entity, performed.sortedBy(PerformedMidiNote::sequence), report)
    }

    suspend fun recent(structureId: String): List<PracticeAttempt> = withContext(Dispatchers.IO) {
        dao.recentForStructure(structureId)
    }

    suspend fun load(id: String): StoredPracticeAttempt = withContext(Dispatchers.IO) {
        val attempt = dao.find(id) ?: error("跟弹记录不存在")
        val performed = dao.eventsForAttempt(id).map { event ->
            PerformedMidiNote(
                sequence = event.sequence,
                channel = event.channel,
                midiPitch = event.midiPitch,
                velocity = event.velocity,
                onsetNanos = event.onsetNanos,
                durationNanos = event.durationNanos,
            )
        }
        val report = PerformanceReportJson.decode(attempt.resultJson)
        require(report.playedCount == performed.size) { "跟弹记录的原始按键不完整" }
        StoredPracticeAttempt(attempt, performed, report)
    }
}
