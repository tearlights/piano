package com.gpiano.app.omr

import android.content.Context
import android.content.pm.ApplicationInfo
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.gpiano.app.data.GpianoDatabaseProvider
import com.gpiano.app.data.RecognitionJob
import com.gpiano.app.data.RecognitionJobStatus
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.net.URI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

class RecognitionRepository(context: Context) {
    private val appContext = context.applicationContext
    private val database = GpianoDatabaseProvider.get(appContext)
    private val dao = database.recognitionJobDao()
    private val inputResolver = RecognitionInputResolver(appContext, database)
    private val workManager = WorkManager.getInstance(appContext)

    fun observeJobs(): Flow<List<RecognitionJob>> = dao.observeAll()

    suspend fun start(scoreId: String, pageIndex: Int = 0): RecognitionJob = withContext(Dispatchers.IO) {
        check(OmrSettingsStore(appContext).load() != null) { "请先在设置中连接 OMR 服务" }
        val input = inputResolver.resolve(scoreId, pageIndex)
        val existing = dao.latestForScore(scoreId)
        if (existing != null && existing.sourcePageIndex == pageIndex && existing.inputSha256 == input.sha256) {
            if (existing.status == RecognitionJobStatus.Pending || existing.status == RecognitionJobStatus.Running) {
                enqueue(existing.id, ExistingWorkPolicy.KEEP)
                return@withContext existing
            }
            if (existing.status == RecognitionJobStatus.Ready && existing.resultStructureId != null) {
                return@withContext existing
            }
        }
        val now = System.currentTimeMillis()
        val job = RecognitionJob(
            id = UUID.randomUUID().toString(),
            sourceScoreId = scoreId,
            sourcePageIndex = pageIndex,
            provider = PROVIDER,
            remoteJobId = null,
            inputSha256 = input.sha256,
            status = RecognitionJobStatus.Pending,
            stage = "waiting",
            attempt = 0,
            resultStructureId = null,
            errorCode = null,
            errorMessage = null,
            diagnosticsJson = null,
            createdAt = now,
            updatedAt = now,
        )
        dao.upsert(job)
        enqueue(job.id, ExistingWorkPolicy.KEEP)
        job
    }

    suspend fun retry(jobId: String) = withContext(Dispatchers.IO) {
        check(OmrSettingsStore(appContext).load() != null) { "请先在设置中连接 OMR 服务" }
        val current = dao.find(jobId) ?: error("识别任务不存在")
        val input = inputResolver.resolve(current.sourceScoreId, current.sourcePageIndex)
        require(input.sha256 == current.inputSha256) { "原谱图片已变化，请创建新的转换任务" }
        dao.update(
            current.copy(
                remoteJobId = null,
                status = RecognitionJobStatus.Pending,
                stage = "waiting",
                resultStructureId = null,
                errorCode = null,
                errorMessage = null,
                diagnosticsJson = null,
                updatedAt = System.currentTimeMillis(),
            ),
        )
        enqueue(jobId, ExistingWorkPolicy.REPLACE)
    }

    suspend fun cancel(jobId: String) = withContext(Dispatchers.IO) {
        workManager.cancelUniqueWork(workName(jobId))
        dao.find(jobId)?.let { current ->
            if (current.status == RecognitionJobStatus.Pending || current.status == RecognitionJobStatus.Running) {
                dao.update(
                    current.copy(
                        status = RecognitionJobStatus.Cancelled,
                        stage = "cancelled",
                        errorCode = null,
                        errorMessage = "转换已取消，可随时重新开始",
                        updatedAt = System.currentTimeMillis(),
                    ),
                )
            }
        }
    }

    private fun enqueue(jobId: String, policy: ExistingWorkPolicy) {
        val endpoint = OmrSettingsStore(appContext).load()?.endpoint.orEmpty()
        val constraints = Constraints.Builder().apply {
            // adb reverse exposes localhost without Android reporting an active network.
            // This exception is limited to Debug loopback endpoints; production OMR still
            // waits for an actual network before WorkManager starts it.
            val debuggable = appContext.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
            if (!isDebugLoopbackEndpoint(endpoint, debuggable)) {
                setRequiredNetworkType(NetworkType.CONNECTED)
            }
        }.build()
        val request = OneTimeWorkRequestBuilder<RecognitionWorker>()
            .setInputData(workDataOf(RecognitionWorker.KEY_JOB_ID to jobId))
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag(WORK_TAG)
            .build()
        workManager.enqueueUniqueWork(workName(jobId), policy, request)
    }

    companion object {
        const val PROVIDER = "audiveris-companion-v1"
        const val WORK_TAG = "gpiano-omr"
        fun workName(jobId: String) = "gpiano-omr-$jobId"
    }
}

internal fun isDebugLoopbackEndpoint(endpoint: String, debuggable: Boolean): Boolean {
    if (!debuggable) return false
    val uri = runCatching { URI(endpoint) }.getOrNull() ?: return false
    return uri.scheme.equals("http", ignoreCase = true) && (
        uri.host.equals("127.0.0.1", ignoreCase = true) ||
            uri.host.equals("localhost", ignoreCase = true) ||
            uri.host == "::1"
        )
}
