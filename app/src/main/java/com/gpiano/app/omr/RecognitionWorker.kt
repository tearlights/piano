package com.gpiano.app.omr

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.gpiano.app.data.GpianoDatabaseProvider
import com.gpiano.app.data.RecognitionJob
import com.gpiano.app.data.RecognitionJobStatus
import com.gpiano.app.scoreworkspace.MusicXmlDocument
import com.gpiano.app.scoreworkspace.MusicXmlCompatibilityNormalizer
import com.gpiano.app.scoreworkspace.MusicXmlScoreParser
import com.gpiano.app.scoreworkspace.ScoreIrValidator
import com.gpiano.app.scoreworkspace.StructuredScoreRepository
import com.gpiano.app.scoreworkspace.toSummary
import java.io.IOException
import java.util.concurrent.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

class RecognitionWorker(
    appContext: Context,
    parameters: WorkerParameters,
) : CoroutineWorker(appContext, parameters) {
    private val database = GpianoDatabaseProvider.get(appContext)
    private val dao = database.recognitionJobDao()
    private val inputResolver = RecognitionInputResolver(appContext, database)
    private val client: OmrClient = AudiverisOmrClient()
    private val structuredRepository = StructuredScoreRepository(appContext, database)

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val jobId = inputData.getString(KEY_JOB_ID) ?: return@withContext Result.failure()
        val original = dao.find(jobId) ?: return@withContext Result.failure()
        if (original.status == RecognitionJobStatus.Ready || original.status == RecognitionJobStatus.Cancelled) {
            return@withContext Result.success()
        }
        val settings = OmrSettingsStore(applicationContext).load()
            ?: return@withContext fail(original, "configuration_missing", "请先在设置中连接 OMR 服务")

        try {
            val input = inputResolver.resolve(original.sourceScoreId, original.sourcePageIndex)
            require(input.sha256 == original.inputSha256) { "原谱图片已变化，请重新发起转换" }
            var current = original.copy(
                status = RecognitionJobStatus.Running,
                stage = if (original.remoteJobId == null) "uploading" else "recognizing",
                attempt = runAttemptCount + 1,
                errorCode = null,
                errorMessage = null,
                updatedAt = System.currentTimeMillis(),
            ).also { dao.update(it) }

            var remoteId = current.remoteJobId
            if (remoteId == null) {
                val remote = client.createJob(settings, input.file, input.mimeType, input.score.fileName)
                remoteId = remote.id
                current = current.copy(
                    remoteJobId = remote.id,
                    stage = remote.stage,
                    diagnosticsJson = remote.diagnosticsJson,
                    updatedAt = System.currentTimeMillis(),
                ).also { dao.update(it) }
            }

            val pollStarted = System.currentTimeMillis()
            while (System.currentTimeMillis() - pollStarted < MAX_POLL_WINDOW_MS) {
                val remote = client.getJob(settings, remoteId)
                current = current.copy(
                    status = RecognitionJobStatus.Running,
                    stage = remote.stage,
                    errorCode = remote.errorCode,
                    errorMessage = remote.errorMessage,
                    diagnosticsJson = remote.diagnosticsJson,
                    updatedAt = System.currentTimeMillis(),
                ).also { dao.update(it) }
                when (remote.status) {
                    "queued", "running" -> delay(POLL_INTERVAL_MS)
                    "failed" -> return@withContext fail(
                        current,
                        remote.errorCode ?: "recognition_failed",
                        remote.errorMessage ?: "Audiveris 未能识别这张谱面",
                        remote.diagnosticsJson,
                    )
                    "ready" -> return@withContext installResult(current, settings, remoteId, input.score.title)
                    else -> throw OmrServiceException("OMR 服务返回未知状态", "invalid_status", false)
                }
            }
            dao.update(
                current.copy(
                    status = RecognitionJobStatus.Pending,
                    stage = "waiting-for-result",
                    errorCode = null,
                    errorMessage = "识别仍在进行，稍后会自动继续",
                    updatedAt = System.currentTimeMillis(),
                ),
            )
            Result.retry()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            val latest = dao.find(jobId) ?: original
            val retryable = error is IOException || error is OmrServiceException && error.retryable
            if (retryable && runAttemptCount < MAX_RETRIES) {
                dao.update(
                    latest.copy(
                        status = RecognitionJobStatus.Pending,
                        stage = "connection-interrupted",
                        errorCode = (error as? OmrServiceException)?.code ?: "network_error",
                        errorMessage = "连接中断，稍后自动重试",
                        updatedAt = System.currentTimeMillis(),
                    ),
                )
                Result.retry()
            } else {
                fail(
                    latest,
                    (error as? OmrServiceException)?.code ?: "invalid_result",
                    error.message ?: "无法完成谱面转换",
                )
            }
        }
    }

    private suspend fun installResult(
        job: RecognitionJob,
        settings: OmrSettings,
        remoteId: String,
        fallbackTitle: String,
    ): Result {
        val xml = MusicXmlCompatibilityNormalizer.normalize(client.downloadMusicXml(settings, remoteId))
        val score = MusicXmlScoreParser.parse(xml)
        ScoreIrValidator.requireValid(score)
        val document = MusicXmlDocument(
            sourceName = "$fallbackTitle.musicxml",
            xml = xml,
            score = score,
            summary = score.toSummary(),
        )
        val session = structuredRepository.openOrCreate(
            sourceKey = "omr-job:${job.id}",
            initialDocument = document,
            sourceScoreId = job.sourceScoreId,
            recognitionStatus = "needs-correction",
            recognitionConfidence = null,
        )
        dao.update(
            job.copy(
                status = RecognitionJobStatus.Ready,
                stage = "needs-correction",
                resultStructureId = session.structure.id,
                errorCode = null,
                errorMessage = null,
                updatedAt = System.currentTimeMillis(),
            ),
        )
        return Result.success()
    }

    private suspend fun fail(
        job: RecognitionJob,
        code: String,
        message: String,
        diagnosticsJson: String? = job.diagnosticsJson,
    ): Result {
        dao.update(
            job.copy(
                status = RecognitionJobStatus.Failed,
                stage = "failed",
                errorCode = code,
                errorMessage = message.take(MAX_ERROR_CHARS),
                diagnosticsJson = diagnosticsJson,
                updatedAt = System.currentTimeMillis(),
            ),
        )
        return Result.failure()
    }

    companion object {
        const val KEY_JOB_ID = "recognitionJobId"
        private const val POLL_INTERVAL_MS = 5_000L
        private const val MAX_POLL_WINDOW_MS = 8L * 60 * 1_000
        private const val MAX_RETRIES = 5
        private const val MAX_ERROR_CHARS = 500
    }
}
