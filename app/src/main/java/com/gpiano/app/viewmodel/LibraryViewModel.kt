package com.gpiano.app.viewmodel

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.gpiano.app.data.Score
import com.gpiano.app.data.ScoreRepository
import com.gpiano.app.data.RecognitionJob
import com.gpiano.app.omr.AudiverisOmrClient
import com.gpiano.app.omr.OmrSettingsStore
import com.gpiano.app.omr.OmrSettings
import com.gpiano.app.omr.RecognitionRepository
import com.gpiano.app.ai.PracticeAiClient
import com.gpiano.app.ai.PracticeAiSettingsStore
import com.gpiano.app.ai.PracticeAiSettings
import android.content.pm.ApplicationInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class BackupUiState(
    val inProgress: Boolean = false,
    val message: String? = null,
    val isError: Boolean = false,
)

data class OmrSettingsUiState(
    val endpoint: String = "",
    val configured: Boolean = false,
    val inProgress: Boolean = false,
    val message: String? = null,
    val isError: Boolean = false,
)

data class AiSettingsUiState(
    val endpoint: String = "",
    val configured: Boolean = false,
    val inProgress: Boolean = false,
    val message: String? = null,
    val isError: Boolean = false,
)

class LibraryViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = ScoreRepository(application)
    private val recognitionRepository = RecognitionRepository(application)
    private val omrSettingsStore = OmrSettingsStore(application)
    private val aiSettingsStore = PracticeAiSettingsStore(application)

    val scores: StateFlow<List<Score>> = repository.observeScores()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _importError = MutableStateFlow<String?>(null)
    val importError = _importError.asStateFlow()
    private val _importInProgress = MutableStateFlow(false)
    val importInProgress = _importInProgress.asStateFlow()

    private val _folderError = MutableStateFlow<String?>(null)
    val folderError = _folderError.asStateFlow()

    private val _backupState = MutableStateFlow(BackupUiState())
    val backupState = _backupState.asStateFlow()

    val recognitionJobs: StateFlow<List<RecognitionJob>> = recognitionRepository.observeJobs()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val existingOmrSettings = omrSettingsStore.load()
    private val debugDefaultEndpoint = if (
        application.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
    ) "http://127.0.0.1:8765" else ""
    private val _omrSettingsState = MutableStateFlow(
        OmrSettingsUiState(
            endpoint = existingOmrSettings?.endpoint ?: debugDefaultEndpoint,
            configured = existingOmrSettings != null,
        ),
    )
    val omrSettingsState = _omrSettingsState.asStateFlow()

    private val existingAiSettings = aiSettingsStore.load()
    private val debugDefaultAiEndpoint = if (
        application.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
    ) "http://127.0.0.1:8766" else ""
    private val _aiSettingsState = MutableStateFlow(
        AiSettingsUiState(
            endpoint = existingAiSettings?.endpoint ?: debugDefaultAiEndpoint,
            configured = existingAiSettings != null,
        ),
    )
    val aiSettingsState = _aiSettingsState.asStateFlow()

    fun exportBackup(uri: Uri) {
        if (_backupState.value.inProgress) return
        viewModelScope.launch {
            _backupState.value = BackupUiState(inProgress = true, message = "正在生成并校验备份…")
            runCatching { repository.exportBackup(uri) }
                .onSuccess { _backupState.value = BackupUiState(message = "备份已导出") }
                .onFailure { _backupState.value = BackupUiState(message = it.message ?: "导出失败", isError = true) }
        }
    }

    fun restoreBackup(uri: Uri) {
        if (_backupState.value.inProgress) return
        viewModelScope.launch {
            _backupState.value = BackupUiState(inProgress = true, message = "正在校验并恢复备份…")
            runCatching { repository.restoreBackup(uri) }
                .onSuccess { _backupState.value = BackupUiState(message = "恢复完成") }
                .onFailure {
                    val message = it.message ?: "恢复失败"
                    _backupState.value = BackupUiState(message = message, isError = true)
                    _importError.value = message
                }
        }
    }

    fun saveAndTestOmrSettings(endpoint: String, token: String) {
        if (_omrSettingsState.value.inProgress) return
        viewModelScope.launch(Dispatchers.IO) {
            _omrSettingsState.value = _omrSettingsState.value.copy(inProgress = true, message = "正在检查 OMR 服务…", isError = false)
            runCatching {
                val existing = omrSettingsStore.load()
                val effectiveToken = token.ifBlank { existing?.token ?: error("首次连接必须填写访问令牌") }
                val proposed = OmrSettings(omrSettingsStore.validateEndpoint(endpoint), effectiveToken)
                check(AudiverisOmrClient().health(proposed) == "ok") { "OMR 服务尚未就绪" }
                omrSettingsStore.save(proposed.endpoint, proposed.token)
            }.onSuccess { settings ->
                _omrSettingsState.value = OmrSettingsUiState(
                    endpoint = settings.endpoint,
                    configured = true,
                    message = "Audiveris OMR 服务已连接",
                )
            }.onFailure { error ->
                _omrSettingsState.value = _omrSettingsState.value.copy(
                    inProgress = false,
                    message = error.message ?: "无法连接 OMR 服务",
                    isError = true,
                )
            }
        }
    }

    fun saveAndTestAiSettings(endpoint: String, token: String) {
        if (_aiSettingsState.value.inProgress) return
        viewModelScope.launch(Dispatchers.IO) {
            _aiSettingsState.value = _aiSettingsState.value.copy(
                inProgress = true,
                message = "正在检查 AI 练习服务…",
                isError = false,
            )
            runCatching {
                val existing = aiSettingsStore.load()
                val effectiveToken = token.ifBlank { existing?.token ?: error("首次连接必须填写访问令牌") }
                val proposed = PracticeAiSettings(aiSettingsStore.validateEndpoint(endpoint), effectiveToken)
                check(PracticeAiClient().health(proposed) == "ok") { "AI 练习服务尚未就绪" }
                aiSettingsStore.save(proposed.endpoint, proposed.token)
            }.onSuccess { settings ->
                _aiSettingsState.value = AiSettingsUiState(
                    endpoint = settings.endpoint,
                    configured = true,
                    message = "AI 练习服务已连接",
                )
            }.onFailure { error ->
                _aiSettingsState.value = _aiSettingsState.value.copy(
                    inProgress = false,
                    message = error.message ?: "无法连接 AI 练习服务",
                    isError = true,
                )
            }
        }
    }

    fun recognize(score: Score) {
        viewModelScope.launch {
            _importError.value = null
            runCatching { recognitionRepository.start(score.id) }
                .onFailure { _importError.value = it.message ?: "无法开始谱面转换" }
        }
    }

    fun retryRecognition(jobId: String) {
        viewModelScope.launch {
            _importError.value = null
            runCatching { recognitionRepository.retry(jobId) }
                .onFailure { _importError.value = it.message ?: "无法重新开始谱面转换" }
        }
    }

    fun cancelRecognition(jobId: String) {
        viewModelScope.launch { recognitionRepository.cancel(jobId) }
    }

    fun importAll(uris: List<Uri>) {
        if (uris.isEmpty() || !beginImport(_importInProgress)) return
        _importError.value = null
        viewModelScope.launch {
            try {
                runCatching { repository.importImageGroup(uris) }
                    .onFailure { _importError.value = it.message ?: "导入失败，请重试" }
            } finally {
                _importInProgress.value = false
            }
        }
    }

    fun import(uri: Uri) {
        if (!beginImport(_importInProgress)) return
        _importError.value = null
        viewModelScope.launch {
            try {
                runCatching { repository.import(uri) }
                    .onFailure { _importError.value = it.message ?: "导入失败，请重试" }
            } finally {
                _importInProgress.value = false
            }
        }
    }

    val folders: StateFlow<List<com.gpiano.app.data.Folder>> = repository.observeFolders()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun moveToFolder(score: Score, folderId: String?) { viewModelScope.launch { repository.moveToFolder(score.id, folderId) } }

    fun rename(score: Score, title: String) {
        viewModelScope.launch {
            runCatching { repository.rename(score.id, title) }
                .onFailure { _importError.value = it.message ?: "重命名失败，请重试" }
        }
    }

    fun createFolder(name: String) {
        viewModelScope.launch {
            _folderError.value = null
            runCatching { repository.createFolder(name) }
                .onFailure { _folderError.value = it.message ?: "创建文件夹失败，请重试" }
        }
    }

    fun clearFolderError() { _folderError.value = null }

    val favorites: StateFlow<List<Score>> = repository.observeFavorites()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun toggleBookmark(score: Score, page: Int) { viewModelScope.launch { repository.toggleBookmark(score.id, page) } }

    fun delete(score: Score) { viewModelScope.launch { repository.delete(score) } }

    fun toggleFavorite(score: Score) { viewModelScope.launch { repository.toggleFavorite(score.id) } }

    fun clearImportError() { _importError.value = null }

    fun open(score: Score) {
        viewModelScope.launch { repository.markOpened(score) }
    }
}

internal fun beginImport(state: MutableStateFlow<Boolean>): Boolean = state.compareAndSet(expect = false, update = true)
