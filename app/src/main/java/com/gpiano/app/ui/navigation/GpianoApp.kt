package com.gpiano.app.ui.navigation

import androidx.activity.compose.BackHandler
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.viewmodel.compose.viewModel
import com.gpiano.app.viewmodel.LibraryViewModel
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import com.gpiano.app.scoreworkspace.WorkspaceSelectionStore
import com.gpiano.app.ui.screens.ImportedLibraryScreen
import com.gpiano.app.ui.screens.ReaderScreen
import com.gpiano.app.ui.screens.RealFavoritesScreen
import com.gpiano.app.ui.screens.RealFoldersScreen
import com.gpiano.app.ui.screens.RestoreSettingsScreen
import com.gpiano.app.ui.screens.StructuredScoreWorkspaceScreen

private enum class Destination(val label: String, val icon: ImageVector) {
    Library("曲谱库", Icons.Outlined.LibraryMusic),
    Favorites("收藏", Icons.Outlined.FavoriteBorder),
    Folders("文件夹", Icons.Outlined.FolderOpen),
    Workspace("练习工作区", Icons.Outlined.MusicNote),
    Settings("设置", Icons.Outlined.Settings),
}

@Composable
fun GpianoApp() {
    val context = LocalContext.current.applicationContext
    val workspaceSelectionStore = remember { WorkspaceSelectionStore(context) }
    var destination by rememberSaveable { mutableStateOf(Destination.Library) }
    var openedScoreId by rememberSaveable { mutableStateOf<String?>(null) }
    val libraryViewModel: LibraryViewModel = viewModel()
    val scores by libraryViewModel.scores.collectAsState()
    val favorites by libraryViewModel.favorites.collectAsState()
    val folders by libraryViewModel.folders.collectAsState()
    val folderError by libraryViewModel.folderError.collectAsState()
    val backupState by libraryViewModel.backupState.collectAsState()
    val recognitionJobs by libraryViewModel.recognitionJobs.collectAsState()
    val omrSettingsState by libraryViewModel.omrSettingsState.collectAsState()
    val aiSettingsState by libraryViewModel.aiSettingsState.collectAsState()
    val importInProgress by libraryViewModel.importInProgress.collectAsState()
    val openedScore = scores.firstOrNull { it.id == openedScoreId }
    var autoRestoreWorkspace by rememberSaveable { mutableStateOf(workspaceSelectionStore.autoRestoreEnabled()) }
    var workspaceStructureId by rememberSaveable {
        mutableStateOf(workspaceSelectionStore.load().takeIf { autoRestoreWorkspace })
    }

    if (openedScoreId != null) {
        BackHandler { openedScoreId = null }
        ReaderScreen(score = openedScore, onBack = { openedScoreId = null }, onToggleFavorite = { openedScore?.let(libraryViewModel::toggleFavorite) }, isFavorite = openedScore?.isFavorite == true, onMoveToFolder = { folderId -> openedScore?.let { libraryViewModel.moveToFolder(it, folderId) } }, onDelete = { openedScore?.let(libraryViewModel::delete); openedScoreId = null })
        return
    }

    Scaffold(
        bottomBar = {
            NavigationBar {
                Destination.entries.forEach { item ->
                    NavigationBarItem(
                        selected = destination == item,
                        onClick = {
                            if (item == Destination.Workspace && destination != Destination.Workspace) {
                                workspaceStructureId = workspaceSelectionStore.load().takeIf { autoRestoreWorkspace }
                            }
                            destination = item
                        },
                        icon = { Icon(item.icon, contentDescription = item.label) },
                        label = { Text(item.label) },
                        colors = NavigationBarItemDefaults.colors(),
                    )
                }
            }
        },
    ) { padding ->
        when (destination) {
            Destination.Library -> ImportedLibraryScreen(
                contentPadding = padding,
                scores = scores,
                importError = libraryViewModel.importError.collectAsState().value,
                importInProgress = importInProgress,
                onImport = libraryViewModel::import,
                onImportAll = libraryViewModel::importAll,
                onRename = libraryViewModel::rename,
                onOpenReader = { score -> libraryViewModel.open(score); openedScoreId = score.id },
                recognitionJobs = recognitionJobs,
                onRecognize = libraryViewModel::recognize,
                onRetryRecognition = libraryViewModel::retryRecognition,
                onCancelRecognition = libraryViewModel::cancelRecognition,
                onOpenWorkspace = { structureId ->
                    workspaceStructureId = structureId
                    workspaceSelectionStore.save(structureId)
                    destination = Destination.Workspace
                },
            )
            Destination.Favorites -> RealFavoritesScreen(contentPadding = padding, scores = favorites, onOpenReader = { score -> libraryViewModel.open(score); openedScoreId = score.id })
            Destination.Folders -> RealFoldersScreen(
                contentPadding = padding,
                folders = folders,
                folderError = folderError,
                onNameChange = libraryViewModel::clearFolderError,
                onCreate = libraryViewModel::createFolder,
            )
            Destination.Workspace -> StructuredScoreWorkspaceScreen(
                contentPadding = padding,
                structureId = workspaceStructureId,
                autoRestoreEnabled = autoRestoreWorkspace,
                onSelectStructure = { structureId ->
                    workspaceStructureId = structureId
                    workspaceSelectionStore.save(structureId)
                },
                onChooseAnotherScore = { workspaceStructureId = null },
                onOpenLibrary = { destination = Destination.Library },
                onAutoRestoreChange = { enabled ->
                    autoRestoreWorkspace = enabled
                    workspaceSelectionStore.setAutoRestoreEnabled(enabled)
                },
            )
            Destination.Settings -> RestoreSettingsScreen(
                contentPadding = padding,
                backupState = backupState,
                onExport = libraryViewModel::exportBackup,
                onRestore = libraryViewModel::restoreBackup,
                omrState = omrSettingsState,
                onSaveOmr = libraryViewModel::saveAndTestOmrSettings,
                aiState = aiSettingsState,
                onSaveAi = libraryViewModel::saveAndTestAiSettings,
            )
        }
    }
}
