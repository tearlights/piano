package com.gpiano.app.viewmodel

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.gpiano.app.data.Score
import com.gpiano.app.data.ScoreRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class LibraryViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = ScoreRepository(application)

    val scores: StateFlow<List<Score>> = repository.observeScores()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _importError = MutableStateFlow<String?>(null)
    val importError = _importError.asStateFlow()

    private val _folderError = MutableStateFlow<String?>(null)
    val folderError = _folderError.asStateFlow()


    fun restoreBackup(uri: Uri) { viewModelScope.launch { runCatching { repository.restoreBackup(uri) }.onFailure { _importError.value = it.message ?: "恢复失败" } } }

    fun importAll(uris: List<Uri>) { viewModelScope.launch { runCatching { repository.importImageGroup(uris) }.onFailure { _importError.value = it.message ?: "导入失败，请重试" } } }

    fun import(uri: Uri) {
        viewModelScope.launch {
            runCatching { repository.import(uri) }
                .onFailure { _importError.value = it.message ?: "导入失败，请重试" }
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
