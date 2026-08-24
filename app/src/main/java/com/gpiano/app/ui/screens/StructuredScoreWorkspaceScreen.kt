package com.gpiano.app.ui.screens

import alphaTab.AlphaTabView
import alphaTab.NotationElement
import alphaTab.collections.DoubleList
import alphaTab.core.ecmaScript.Uint8Array
import alphaTab.importer.ScoreLoader
import alphaTab.IScrollHandler
import alphaTab.midi.MidiTickLookupFindBeatResultCursorMode
import alphaTab.rendering.utils.BeatBounds
import android.util.Log
import android.view.View
import android.widget.ScrollView
import android.widget.HorizontalScrollView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.gpiano.app.data.PracticeVersion
import com.gpiano.app.data.PracticeVersionRevision
import com.gpiano.app.data.PracticeVersionStatus
import com.gpiano.app.data.ScoreStructure
import com.gpiano.app.scoreworkspace.CorrectionOperation
import com.gpiano.app.scoreworkspace.MusicXmlSummary
import com.gpiano.app.scoreworkspace.MusicalDuration
import com.gpiano.app.scoreworkspace.PersistentScoreSession
import com.gpiano.app.scoreworkspace.PracticeAnalysis
import com.gpiano.app.scoreworkspace.PracticeGuidance
import com.gpiano.app.scoreworkspace.PracticeEditPlan
import com.gpiano.app.scoreworkspace.PracticeVersionDocument
import com.gpiano.app.scoreworkspace.PracticeVersionRepository
import com.gpiano.app.scoreworkspace.RecommendedPlayback
import com.gpiano.app.scoreworkspace.PlaybackHand
import com.gpiano.app.scoreworkspace.PlaybackPlan
import com.gpiano.app.scoreworkspace.PlaybackPlanCompiler
import com.gpiano.app.scoreworkspace.PlaybackSelection
import com.gpiano.app.scoreworkspace.ScoreEventIr
import com.gpiano.app.scoreworkspace.ScoreHand
import com.gpiano.app.scoreworkspace.ScoreIr
import com.gpiano.app.scoreworkspace.ScorePracticeAnalyzer
import com.gpiano.app.scoreworkspace.ScorePitch
import com.gpiano.app.scoreworkspace.SourceScorePage
import com.gpiano.app.scoreworkspace.StandardMidiFile
import com.gpiano.app.scoreworkspace.StructuredScoreRepository
import com.gpiano.app.scoreworkspace.toSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.alphatab.R as AlphaTabR
import org.json.JSONObject
import kotlin.contracts.ExperimentalContracts
import kotlin.math.roundToInt

private sealed interface WorkspaceLoadState {
    data object Loading : WorkspaceLoadState
    data class Ready(val session: PersistentScoreSession) : WorkspaceLoadState
    data class Failed(val message: String) : WorkspaceLoadState
}

private sealed interface ScoreRenderState {
    data object Loading : ScoreRenderState
    data object Ready : ScoreRenderState
    data class Failed(val message: String) : ScoreRenderState
}

@Composable
@OptIn(ExperimentalMaterial3Api::class, ExperimentalContracts::class, ExperimentalUnsignedTypes::class)
fun StructuredScoreWorkspaceScreen(
    contentPadding: PaddingValues,
    structureId: String?,
    autoRestoreEnabled: Boolean,
    onSelectStructure: (String) -> Unit,
    onChooseAnotherScore: () -> Unit,
    onOpenLibrary: () -> Unit,
    onAutoRestoreChange: (Boolean) -> Unit,
) {
    val context = LocalContext.current.applicationContext
    val repository = remember { StructuredScoreRepository(context) }
    val practiceVersionRepository = remember { PracticeVersionRepository(context) }
    val playbackController = remember { AlphaTabPlaybackController() }
    val editGate = remember { OperationGate() }
    val practiceVersionGate = remember { OperationGate() }
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf<WorkspaceLoadState>(WorkspaceLoadState.Loading) }
    var availableStructures by remember { mutableStateOf<List<ScoreStructure>>(emptyList()) }
    var structureListLoading by remember { mutableStateOf(false) }
    var structureListError by remember { mutableStateOf<String?>(null) }
    var selectedMeasure by remember { mutableIntStateOf(1) }
    var correctionVisible by remember { mutableStateOf(false) }
    var historyVisible by remember { mutableStateOf(false) }
    var guidanceVisible by remember { mutableStateOf(false) }
    var aiVisible by remember { mutableStateOf(false) }
    var practiceVersionsVisible by remember { mutableStateOf(false) }
    var midiPracticeVisible by remember { mutableStateOf(false) }
    var midiPlan by remember { mutableStateOf<PlaybackPlan?>(null) }
    var showSource by remember(structureId) { mutableStateOf(false) }
    var playbackSettingsVisible by remember { mutableStateOf(false) }
    var playbackEndMeasure by remember { mutableIntStateOf(1) }
    var playbackSpeed by remember { mutableStateOf(0.75) }
    var playbackHand by remember { mutableStateOf(PlaybackHand.Both) }
    var playbackLooping by remember { mutableStateOf(false) }
    var playerState by remember { mutableStateOf(ScorePlayerUiState()) }
    var selectedEventId by remember { mutableStateOf<String?>(null) }
    var editInProgress by remember { mutableStateOf(false) }
    var editError by remember { mutableStateOf<String?>(null) }
    var practiceVersions by remember(structureId) { mutableStateOf<List<PracticeVersion>>(emptyList()) }
    var inspectedPracticeVersion by remember(structureId) { mutableStateOf<PracticeVersionDocument?>(null) }
    var activePracticeVersion by remember(structureId) { mutableStateOf<PracticeVersionDocument?>(null) }
    var practiceVersionBusy by remember { mutableStateOf(false) }
    var practiceVersionError by remember { mutableStateOf<String?>(null) }
    var mainExportBusy by remember { mutableStateOf(false) }
    var mainExportMessage by remember { mutableStateOf<String?>(null) }
    var mainExportFailed by remember { mutableStateOf(false) }
    var mainExportSession by remember { mutableStateOf<PersistentScoreSession?>(null) }
    var midiExportPlan by remember { mutableStateOf<PlaybackPlan?>(null) }
    var midiExportBusy by remember { mutableStateOf(false) }
    var midiExportMessage by remember { mutableStateOf<String?>(null) }
    var midiExportFailed by remember { mutableStateOf(false) }
    var exportPracticeVersionId by remember { mutableStateOf<String?>(null) }
    val exportMainRevision = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/vnd.recordare.musicxml+xml"),
    ) { destination ->
        val captured = mainExportSession
        mainExportSession = null
        if (destination != null && captured != null) {
            scope.launch {
                mainExportBusy = true
                mainExportFailed = false
                mainExportMessage = "正在导出当前修订…"
                runCatching { repository.export(captured, destination) }
                    .onSuccess { mainExportMessage = "当前修订已导出为标准 MusicXML" }
                    .onFailure {
                        mainExportFailed = true
                        mainExportMessage = it.message ?: "无法导出当前修订"
                    }
                mainExportBusy = false
            }
        }
    }
    val exportPracticeVersion = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/vnd.recordare.musicxml+xml"),
    ) { destination ->
        val id = exportPracticeVersionId
        exportPracticeVersionId = null
        if (destination != null && id != null) {
            scope.launch {
                try {
                    runCatching { practiceVersionRepository.export(id, destination) }
                        .onFailure { practiceVersionError = it.message ?: "无法导出 MusicXML" }
                } finally {
                    practiceVersionGate.leave()
                    practiceVersionBusy = false
                }
            }
        } else if (id != null) {
            practiceVersionGate.leave()
            practiceVersionBusy = false
        }
    }
    val exportMidi = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("audio/x-midi"),
    ) { destination ->
        val captured = midiExportPlan
        midiExportPlan = null
        if (destination != null && captured != null) {
            scope.launch {
                midiExportBusy = true
                midiExportFailed = false
                midiExportMessage = "正在生成标准 MIDI 文件…"
                runCatching {
                    withContext(Dispatchers.IO) {
                        val bytes = StandardMidiFile.encode(captured)
                        context.contentResolver.openOutputStream(destination, "w")?.use { output ->
                            output.write(bytes)
                            output.flush()
                        } ?: error("无法写入 MIDI 文件")
                    }
                }.onSuccess {
                    midiExportMessage = "当前片段已导出为标准 MIDI 文件"
                }.onFailure {
                    midiExportFailed = true
                    midiExportMessage = it.message ?: "无法导出 MIDI 文件"
                }
                midiExportBusy = false
            }
        }
    }

    playbackController.onStateChanged = { playerState = it }
    DisposableEffect(playbackController) {
        onDispose { playbackController.detach() }
    }

    val applyOperation: (CorrectionOperation) -> Unit = { operation ->
        val capturedPracticeVersion = activePracticeVersion
        val captured = (state as? WorkspaceLoadState.Ready)?.session
        if (captured != null && editGate.tryEnter()) {
            editInProgress = true
            editError = null
            scope.launch {
                try {
                    runCatching {
                        withContext(Dispatchers.Default) {
                            if (capturedPracticeVersion != null) {
                                practiceVersionRepository.apply(capturedPracticeVersion, operation)
                            } else {
                                repository.apply(captured, operation)
                            }
                        }
                    }.onSuccess { revised ->
                        if (revised is PracticeVersionDocument) {
                            activePracticeVersion = revised
                            inspectedPracticeVersion = revised
                        } else if (revised is PersistentScoreSession) {
                            state = WorkspaceLoadState.Ready(revised)
                            activePracticeVersion = null
                        }
                    }.onFailure { error -> editError = error.message ?: "无法应用这次校正" }
                } finally {
                    editGate.leave()
                    editInProgress = false
                }
            }
        }
    }
    val undoRevision: () -> Unit = {
        val capturedPracticeVersion = activePracticeVersion
        val captured = (state as? WorkspaceLoadState.Ready)?.session
        if (captured != null && editGate.tryEnter()) {
            editInProgress = true
            editError = null
            scope.launch {
                try {
                    runCatching {
                        if (capturedPracticeVersion != null) practiceVersionRepository.undo(capturedPracticeVersion)
                        else repository.undo(captured)
                    }.onSuccess { revised ->
                        if (revised is PracticeVersionDocument) {
                            activePracticeVersion = revised
                            inspectedPracticeVersion = revised
                        } else if (revised is PersistentScoreSession) {
                            state = WorkspaceLoadState.Ready(revised)
                            activePracticeVersion = null
                        }
                    }.onFailure { editError = it.message ?: "无法撤销这次校正" }
                } finally {
                    editGate.leave()
                    editInProgress = false
                }
            }
        }
    }
    val redoRevision: () -> Unit = {
        val capturedPracticeVersion = activePracticeVersion
        val captured = (state as? WorkspaceLoadState.Ready)?.session
        if (captured != null && editGate.tryEnter()) {
            editInProgress = true
            editError = null
            scope.launch {
                try {
                    runCatching {
                        if (capturedPracticeVersion != null) practiceVersionRepository.redo(capturedPracticeVersion)
                        else repository.redo(captured)
                    }.onSuccess { revised ->
                        if (revised is PracticeVersionDocument) {
                            activePracticeVersion = revised
                            inspectedPracticeVersion = revised
                        } else if (revised is PersistentScoreSession) {
                            state = WorkspaceLoadState.Ready(revised)
                            activePracticeVersion = null
                        }
                    }.onFailure { editError = it.message ?: "无法重做这次校正" }
                } finally {
                    editGate.leave()
                    editInProgress = false
                }
            }
        }
    }

    LaunchedEffect(structureId, repository) {
        if (structureId == null) {
            playbackController.stop()
            structureListLoading = true
            structureListError = null
            availableStructures = runCatching { repository.listStructures() }
                .onFailure { structureListError = it.message ?: "无法读取练习谱列表" }
                .getOrDefault(emptyList())
            structureListLoading = false
            return@LaunchedEffect
        }
        state = WorkspaceLoadState.Loading
        state = runCatching { repository.open(structureId) }
            .fold(
                onSuccess = {
                    selectedMeasure = 1
                    playbackEndMeasure = 1
                    activePracticeVersion = null
                    showSource = false
                    WorkspaceLoadState.Ready(it)
                },
                onFailure = { WorkspaceLoadState.Failed(it.message ?: "无法读取结构化乐谱") },
            )
    }

    LaunchedEffect(structureId, practiceVersionRepository) {
        if (structureId != null) {
            practiceVersions = runCatching { practiceVersionRepository.list(structureId) }.getOrDefault(emptyList())
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding)
            .background(MaterialTheme.colorScheme.background),
    ) {
        if (structureId == null) {
            WorkspaceScorePicker(
                structures = availableStructures,
                loading = structureListLoading,
                error = structureListError,
                autoRestoreEnabled = autoRestoreEnabled,
                onSelectStructure = onSelectStructure,
                onOpenLibrary = onOpenLibrary,
                onAutoRestoreChange = onAutoRestoreChange,
            )
        } else when (val currentState = state) {
            WorkspaceLoadState.Loading -> LoadingWorkspace()
            is WorkspaceLoadState.Failed -> FailedWorkspace(currentState.message)
            is WorkspaceLoadState.Ready -> {
                val displayedScore = activePracticeVersion?.document?.score ?: currentState.session.score
                WorkspaceContent(
                    session = currentState.session,
                    practiceVersion = activePracticeVersion,
                    selectedMeasure = selectedMeasure,
                    playbackEndMeasure = playbackEndMeasure,
                    playerState = playerState,
                    playbackController = playbackController,
                    showSource = showSource,
                    onToggleSource = {
                        playbackController.stop()
                        correctionVisible = false
                        playbackSettingsVisible = false
                        guidanceVisible = false
                        practiceVersionsVisible = false
                        midiPracticeVisible = false
                        aiVisible = false
                        showSource = !showSource
                    },
                    onChooseAnotherScore = {
                        playbackController.stop()
                        correctionVisible = false
                        playbackSettingsVisible = false
                        guidanceVisible = false
                        practiceVersionsVisible = false
                        midiPracticeVisible = false
                        aiVisible = false
                        onChooseAnotherScore()
                    },
                    onSelectMeasure = {
                        if (playerState.phase == ScorePlayerPhase.Playing || playerState.phase == ScorePlayerPhase.Paused) {
                            playbackController.stop()
                        }
                        selectedMeasure = it
                        playbackEndMeasure = it
                    },
                    onOpenPlaybackSettings = {
                        playbackController.stop()
                        correctionVisible = false
                        guidanceVisible = false
                        practiceVersionsVisible = false
                        midiPracticeVisible = false
                        aiVisible = false
                        playbackSettingsVisible = true
                    },
                    onTogglePlayback = {
                        when (playerState.phase) {
                            ScorePlayerPhase.Playing -> playbackController.pause()
                            ScorePlayerPhase.Paused -> playbackController.resume()
                            else -> runCatching {
                                PlaybackPlanCompiler.compile(
                                    displayedScore,
                                    PlaybackSelection(
                                        startMeasure = selectedMeasure,
                                        endMeasure = playbackEndMeasure,
                                        hand = playbackHand,
                                        speed = playbackSpeed,
                                        looping = playbackLooping,
                                    ),
                                )
                            }.onSuccess(playbackController::start)
                        }
                    },
                    onStopPlayback = playbackController::stop,
                    onOpenCorrection = {
                        playbackController.stop()
                        playbackSettingsVisible = false
                        guidanceVisible = false
                        practiceVersionsVisible = false
                        midiPracticeVisible = false
                        aiVisible = false
                        selectedEventId = displayedScore.eventsInMeasure(selectedMeasure).firstOrNull()?.id
                        editError = null
                        correctionVisible = true
                    },
                    onOpenGuidance = {
                        playbackController.stop()
                        correctionVisible = false
                        playbackSettingsVisible = false
                        practiceVersionsVisible = false
                        midiPracticeVisible = false
                        aiVisible = false
                        guidanceVisible = true
                    },
                    onOpenPracticeVersions = {
                        playbackController.stop()
                        correctionVisible = false
                        playbackSettingsVisible = false
                        guidanceVisible = false
                        practiceVersionError = null
                        midiPracticeVisible = false
                        aiVisible = false
                        practiceVersionsVisible = true
                        scope.launch {
                            practiceVersions = runCatching {
                                practiceVersionRepository.list(currentState.session.structure.id)
                            }.getOrElse {
                                practiceVersionError = it.message ?: "无法读取练习版本"
                                emptyList()
                            }
                        }
                    },
                    onExitPracticeVersion = {
                        playbackController.stop()
                        activePracticeVersion = null
                    },
                    onOpenMidiPractice = {
                        playbackController.stop()
                        correctionVisible = false
                        playbackSettingsVisible = false
                        guidanceVisible = false
                        practiceVersionsVisible = false
                        aiVisible = false
                        midiPlan = runCatching {
                            PlaybackPlanCompiler.compile(
                                displayedScore,
                                PlaybackSelection(
                                    startMeasure = selectedMeasure,
                                    endMeasure = playbackEndMeasure,
                                    hand = playbackHand,
                                    speed = playbackSpeed,
                                    looping = false,
                                ),
                            )
                        }.getOrNull()
                        midiPracticeVisible = true
                    },
                    mainExportBusy = mainExportBusy,
                    mainExportMessage = mainExportMessage,
                    mainExportFailed = mainExportFailed,
                    onExportCurrent = {
                        mainExportSession = currentState.session
                        mainExportMessage = null
                        mainExportFailed = false
                        val safeName = currentState.session.structure.title
                            .replace(Regex("[\\\\/:*?\"<>|]"), "-")
                            .ifBlank { "Gpiano-结构化乐谱" }
                        exportMainRevision.launch("$safeName.musicxml")
                    },
                    onUndo = undoRevision,
                    onRedo = redoRevision,
                    onOpenHistory = { historyVisible = true },
                )
            }
        }

        val ready = state as? WorkspaceLoadState.Ready
        val displayedScore = activePracticeVersion?.document?.score ?: ready?.session?.score
        if (playbackSettingsVisible && ready != null) {
            PlaybackSettingsSheet(
                score = displayedScore ?: ready.session.score,
                startMeasure = selectedMeasure,
                endMeasure = playbackEndMeasure,
                speed = playbackSpeed,
                hand = playbackHand,
                looping = playbackLooping,
                onRangeChange = { start, end ->
                    selectedMeasure = start.coerceIn(1, (displayedScore ?: ready.session.score).measureCount)
                    playbackEndMeasure = end.coerceIn(selectedMeasure, (displayedScore ?: ready.session.score).measureCount)
                },
                onSpeedChange = { playbackSpeed = it },
                onHandChange = { playbackHand = it },
                onLoopingChange = { playbackLooping = it },
                midiExportBusy = midiExportBusy,
                midiExportMessage = midiExportMessage,
                midiExportFailed = midiExportFailed,
                onExportMidi = { plan ->
                    midiExportPlan = plan
                    midiExportMessage = null
                    midiExportFailed = false
                    val base = (activePracticeVersion?.version?.title ?: ready.session.structure.title)
                        .replace(Regex("[\\\\/:*?\"<>|]"), "-")
                        .ifBlank { "Gpiano-练习片段" }
                    val range = if (plan.selection.startMeasure == plan.selection.endMeasure) {
                        "第${plan.selection.startMeasure}小节"
                    } else {
                        "第${plan.selection.startMeasure}-${plan.selection.endMeasure}小节"
                    }
                    exportMidi.launch("$base-$range.mid")
                },
                onDismiss = { playbackSettingsVisible = false },
            )
        }
        if (correctionVisible && ready != null) {
            val correctionPracticeVersion = activePracticeVersion
            val correctionScore = correctionPracticeVersion?.score ?: ready.session.score
            CorrectionSheet(
                score = correctionScore,
                measureIndex = selectedMeasure,
                revisionNumber = correctionPracticeVersion?.revisionNumber ?: ready.session.revisionNumber,
                selectedEventId = selectedEventId,
                editInProgress = editInProgress,
                error = editError,
                onSelectEvent = { selectedEventId = it },
                onApplyOperation = applyOperation,
                onUndo = undoRevision,
                onRedo = redoRevision,
                onOpenHistory = {
                    correctionVisible = false
                    historyVisible = true
                },
                canUndo = correctionPracticeVersion?.canUndo ?: ready.session.canUndo,
                canRedo = correctionPracticeVersion?.canRedo ?: ready.session.canRedo,
                onDismiss = { if (!editInProgress) correctionVisible = false },
            )
        }
        if (historyVisible && ready != null) {
            val historyPracticeVersion = activePracticeVersion
            if (historyPracticeVersion != null) {
                PracticeVersionRevisionHistorySheet(
                    session = historyPracticeVersion,
                    editInProgress = editInProgress,
                    onSelect = { revisionId ->
                        val captured = activePracticeVersion ?: return@PracticeVersionRevisionHistorySheet
                        if (!editGate.tryEnter()) return@PracticeVersionRevisionHistorySheet
                        editInProgress = true
                        editError = null
                        scope.launch {
                            try {
                                runCatching { practiceVersionRepository.checkout(captured, revisionId) }
                                    .onSuccess {
                                        activePracticeVersion = it
                                        inspectedPracticeVersion = it
                                        historyVisible = false
                                    }
                                    .onFailure { editError = it.message ?: "无法切换练习版本修订" }
                            } finally {
                                editGate.leave()
                                editInProgress = false
                            }
                        }
                    },
                    onDismiss = { if (!editInProgress) historyVisible = false },
                )
            } else {
                RevisionHistorySheet(
                    session = ready.session,
                    editInProgress = editInProgress,
                    onSelect = { revisionId ->
                        val captured = (state as? WorkspaceLoadState.Ready)?.session ?: return@RevisionHistorySheet
                        if (!editGate.tryEnter()) return@RevisionHistorySheet
                        editInProgress = true
                        editError = null
                        scope.launch {
                            try {
                                runCatching { repository.checkout(captured, revisionId) }
                                    .onSuccess {
                                        state = WorkspaceLoadState.Ready(it)
                                        activePracticeVersion = null
                                        historyVisible = false
                                    }
                                    .onFailure { editError = it.message ?: "无法切换修订版本" }
                            } finally {
                                editGate.leave()
                                editInProgress = false
                            }
                        }
                    },
                    onDismiss = { if (!editInProgress) historyVisible = false },
                )
            }
        }
        if (guidanceVisible && ready != null) {
            val guidanceScore = displayedScore ?: ready.session.score
            val guidanceXml = activePracticeVersion?.document?.xml ?: ready.session.xml
            val analysis = remember(guidanceXml, selectedMeasure, playbackEndMeasure) {
                ScorePracticeAnalyzer.analyze(
                    guidanceScore,
                    selectedMeasure,
                    playbackEndMeasure.coerceAtLeast(selectedMeasure),
                )
            }
            PracticeGuidanceSheet(
                analysis = analysis,
                onPreview = { recommendation ->
                    playbackSpeed = recommendation.speed
                    playbackHand = recommendation.hand
                    playbackLooping = recommendation.looping
                    runCatching {
                        PlaybackPlanCompiler.compile(
                            guidanceScore,
                            PlaybackSelection(
                                startMeasure = selectedMeasure,
                                endMeasure = playbackEndMeasure,
                                hand = recommendation.hand,
                                speed = recommendation.speed,
                                looping = recommendation.looping,
                            ),
                        )
                    }.onSuccess(playbackController::start)
                },
                onAskAi = {
                    guidanceVisible = false
                    aiVisible = true
                },
                onDismiss = { guidanceVisible = false },
            )
        }
        if (practiceVersionsVisible && ready != null) {
            PracticeVersionsSheet(
                fromMeasure = selectedMeasure,
                toMeasure = playbackEndMeasure,
                versions = practiceVersions,
                inspected = inspectedPracticeVersion,
                busy = practiceVersionBusy,
                error = practiceVersionError,
                onCreate = { preset ->
                    val captured = (state as? WorkspaceLoadState.Ready)?.session ?: return@PracticeVersionsSheet
                    if (!practiceVersionGate.tryEnter()) return@PracticeVersionsSheet
                    practiceVersionBusy = true
                    practiceVersionError = null
                    scope.launch {
                        try {
                            runCatching {
                                practiceVersionRepository.create(
                                    captured,
                                    PracticeEditPlan(preset, selectedMeasure, playbackEndMeasure),
                                )
                            }.onSuccess { created ->
                                inspectedPracticeVersion = created
                                practiceVersions = practiceVersionRepository.list(captured.structure.id)
                            }.onFailure { practiceVersionError = it.message ?: "当前范围无法生成这个练习版本" }
                        } finally {
                            practiceVersionGate.leave()
                            practiceVersionBusy = false
                        }
                    }
                },
                onInspect = { id ->
                    if (!practiceVersionGate.tryEnter()) return@PracticeVersionsSheet
                    practiceVersionBusy = true
                    practiceVersionError = null
                    scope.launch {
                        try {
                            runCatching { practiceVersionRepository.load(id) }
                                .onSuccess { inspectedPracticeVersion = it }
                                .onFailure { practiceVersionError = it.message ?: "无法读取练习版本" }
                        } finally {
                            practiceVersionGate.leave()
                            practiceVersionBusy = false
                        }
                    }
                },
                onPreview = { document ->
                    playbackController.stop()
                    activePracticeVersion = document
                    practiceVersionsVisible = false
                    showSource = false
                    selectedMeasure = document.version.fromMeasure
                    playbackEndMeasure = document.version.toMeasure
                },
                onAccept = { id ->
                    if (!practiceVersionGate.tryEnter()) return@PracticeVersionsSheet
                    practiceVersionBusy = true
                    practiceVersionError = null
                    scope.launch {
                        try {
                            runCatching { practiceVersionRepository.setStatus(id, PracticeVersionStatus.Accepted) }
                                .onSuccess { accepted ->
                                    inspectedPracticeVersion = accepted
                                    if (activePracticeVersion?.version?.id == id) activePracticeVersion = accepted
                                    practiceVersions = practiceVersionRepository.list(ready.session.structure.id)
                                }
                                .onFailure { practiceVersionError = it.message ?: "无法采纳练习版本" }
                        } finally {
                            practiceVersionGate.leave()
                            practiceVersionBusy = false
                        }
                    }
                },
                onReject = { id ->
                    if (!practiceVersionGate.tryEnter()) return@PracticeVersionsSheet
                    practiceVersionBusy = true
                    practiceVersionError = null
                    scope.launch {
                        try {
                            runCatching { practiceVersionRepository.setStatus(id, PracticeVersionStatus.Rejected) }
                                .onSuccess { rejected ->
                                    inspectedPracticeVersion = rejected
                                    if (activePracticeVersion?.version?.id == id) activePracticeVersion = null
                                    practiceVersions = practiceVersionRepository.list(ready.session.structure.id)
                                }
                                .onFailure { practiceVersionError = it.message ?: "无法拒绝练习版本" }
                        } finally {
                            practiceVersionGate.leave()
                            practiceVersionBusy = false
                        }
                    }
                },
                onExport = { version ->
                    if (practiceVersionGate.tryEnter()) {
                        practiceVersionBusy = true
                        practiceVersionError = null
                        exportPracticeVersionId = version.id
                        val safeName = version.title.replace(Regex("[\\\\/:*?\"<>|]"), "-")
                        runCatching { exportPracticeVersion.launch("$safeName.musicxml") }
                            .onFailure {
                                exportPracticeVersionId = null
                                practiceVersionGate.leave()
                                practiceVersionBusy = false
                                practiceVersionError = it.message ?: "无法打开导出位置"
                            }
                    }
                },
                onDismiss = { if (!practiceVersionBusy) practiceVersionsVisible = false },
            )
        }
        if (midiPracticeVisible && ready != null) {
            val plan = midiPlan
            if (plan == null) {
                SimpleMessageSheet(
                    title = "无法开始跟弹",
                    message = "当前选段无法生成匹配时间轴，请调整小节范围或先校正谱面。",
                    onDismiss = { midiPracticeVisible = false },
                )
            } else {
                MidiPracticeSheet(
                    structureId = ready.session.structure.id,
                    sourceRevisionId = activePracticeVersion?.version?.baseRevisionId ?: ready.session.revision.id,
                    practiceVersionId = activePracticeVersion?.version?.id,
                    plan = plan,
                    onListen = {
                        playbackController.stop()
                        playbackController.start(plan.copy(selection = plan.selection.copy(looping = false)))
                    },
                    onBeforeRecord = playbackController::stop,
                    onOpenCorrection = {
                        midiPracticeVisible = false
                        activePracticeVersion = null
                        selectedEventId = ready.session.score.eventsInMeasure(selectedMeasure).firstOrNull()?.id
                        editError = null
                        correctionVisible = true
                    },
                    onDismiss = {
                        playbackController.stop()
                        midiPracticeVisible = false
                    },
                )
            }
        }
        if (aiVisible && ready != null) {
            val aiScore = displayedScore ?: ready.session.score
            val analysis = remember(aiScore, selectedMeasure, playbackEndMeasure) {
                ScorePracticeAnalyzer.analyze(
                    aiScore,
                    selectedMeasure,
                    playbackEndMeasure.coerceAtLeast(selectedMeasure),
                )
            }
            PracticeAiSheet(
                score = aiScore,
                analysis = analysis,
                selection = PlaybackSelection(
                    startMeasure = selectedMeasure,
                    endMeasure = playbackEndMeasure,
                    hand = playbackHand,
                    speed = playbackSpeed,
                    looping = playbackLooping,
                ),
                onPreview = { recommendation ->
                    playbackSpeed = recommendation.speed
                    playbackHand = recommendation.hand
                    playbackLooping = recommendation.looping
                    runCatching {
                        PlaybackPlanCompiler.compile(
                            aiScore,
                            PlaybackSelection(
                                selectedMeasure,
                                playbackEndMeasure,
                                recommendation.hand,
                                recommendation.speed,
                                recommendation.looping,
                            ),
                        )
                    }.onSuccess(playbackController::start)
                },
                onCreatePracticeVersion = if (activePracticeVersion == null) {
                    { preset ->
                        val created = practiceVersionRepository.create(
                            ready.session,
                            PracticeEditPlan(preset, selectedMeasure, playbackEndMeasure),
                        )
                        inspectedPracticeVersion = created
                        practiceVersions = practiceVersionRepository.list(ready.session.structure.id)
                        practiceVersionError = null
                        aiVisible = false
                        practiceVersionsVisible = true
                    }
                } else {
                    null
                },
                onDismiss = {
                    playbackController.stop()
                    aiVisible = false
                },
            )
        }
    }
}

@Composable
private fun WorkspaceScorePicker(
    structures: List<ScoreStructure>,
    loading: Boolean,
    error: String?,
    autoRestoreEnabled: Boolean,
    onSelectStructure: (String) -> Unit,
    onOpenLibrary: () -> Unit,
    onAutoRestoreChange: (Boolean) -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 16.dp)) {
        Text("选择练习谱", style = MaterialTheme.typography.headlineSmall)
        Text(
            "先选择要练习的谱面。最近练习排在最前，进入后也可随时换谱。",
            modifier = Modifier.padding(top = 6.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("自动恢复上次练习", style = MaterialTheme.typography.titleSmall)
                Text(
                    if (autoRestoreEnabled) "下次点开练习工作区时直接恢复最近一谱" else "当前默认：每次先选谱",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = autoRestoreEnabled, onCheckedChange = onAutoRestoreChange)
        }
        HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
        when {
            loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            error != null -> Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
            ) {
                Text(error, modifier = Modifier.padding(16.dp))
            }
            structures.isEmpty() -> Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text("还没有可练习的结构化琴谱", style = MaterialTheme.typography.titleMedium)
                Text(
                    "请先从曲谱库导入图片或 PDF，并完成识谱转换。",
                    modifier = Modifier.padding(top = 8.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(onClick = onOpenLibrary, modifier = Modifier.padding(top = 16.dp)) { Text("前往曲谱库") }
            }
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(structures, key = ScoreStructure::id) { structure ->
                    Card(onClick = { onSelectStructure(structure.id) }, modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(structure.title, style = MaterialTheme.typography.titleMedium)
                                Text(
                                    if (structure == structures.first()) "最近练习" else "结构化练习谱",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Text("打开", color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
                item { Button(onClick = onOpenLibrary, modifier = Modifier.fillMaxWidth()) { Text("从曲谱库添加练习谱") } }
            }
        }
    }
}

@Composable
private fun LoadingWorkspace() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Spacer(Modifier.height(12.dp))
            Text("正在加载结构化乐谱…")
        }
    }
}

@Composable
private fun FailedWorkspace(message: String) {
    Card(
        modifier = Modifier.padding(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text("无法加载结构化乐谱", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Text(message)
            Spacer(Modifier.height(12.dp))
            Text(
                "原有琴谱阅读功能不受影响；可回到曲谱库重试转换或选择其他草稿。",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun WorkspaceContent(
    session: PersistentScoreSession,
    practiceVersion: PracticeVersionDocument?,
    selectedMeasure: Int,
    playbackEndMeasure: Int,
    playerState: ScorePlayerUiState,
    playbackController: AlphaTabPlaybackController,
    showSource: Boolean,
    onToggleSource: () -> Unit,
    onChooseAnotherScore: () -> Unit,
    onSelectMeasure: (Int) -> Unit,
    onOpenPlaybackSettings: () -> Unit,
    onTogglePlayback: () -> Unit,
    onStopPlayback: () -> Unit,
    onOpenCorrection: () -> Unit,
    onOpenGuidance: () -> Unit,
    onOpenPracticeVersions: () -> Unit,
    onExitPracticeVersion: () -> Unit,
    onOpenMidiPractice: () -> Unit,
    mainExportBusy: Boolean,
    mainExportMessage: String?,
    mainExportFailed: Boolean,
    onExportCurrent: () -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onOpenHistory: () -> Unit,
) {
    val displayedDocument = practiceVersion?.document ?: session.document
    val displayedScore = displayedDocument.score
    val summary = displayedDocument.summary
    val selectedMeasureEventCount = remember(displayedScore, selectedMeasure) {
        displayedScore.eventsInMeasure(selectedMeasure).size
    }
    Column(modifier = Modifier.fillMaxSize()) {
        ScoreMetadata(
            summary = summary,
            revisionNumber = practiceVersion?.revisionNumber ?: session.revisionNumber,
            practiceVersion = practiceVersion?.version,
            hasSource = session.sourcePage != null,
            showSource = showSource,
            onToggleSource = onToggleSource,
            onChooseAnotherScore = onChooseAnotherScore,
            onOpenHistory = onOpenHistory,
        )
        MeasureSelector(
            measureCount = summary.measureCount,
            selectedMeasure = selectedMeasure,
            playbackMeasure = playerState.currentMeasure,
            onSelectMeasure = onSelectMeasure,
        )
        HorizontalDivider()
        if (showSource && session.sourcePage != null) {
            OriginalScoreView(session.sourcePage, Modifier.weight(1f))
        } else {
            ScoreRenderer(
                xml = displayedDocument.xml,
                score = displayedScore,
                selectedMeasure = selectedMeasure,
                playbackMeasure = playerState.currentMeasure,
                playbackController = playbackController,
                modifier = Modifier.weight(1f),
            )
        }
        HorizontalDivider()
        SelectedMeasureStatus(
            selectedMeasure = selectedMeasure,
            eventCount = selectedMeasureEventCount,
            statusMessage = practiceVersion?.recoveryMessage ?: session.recoveryMessage,
            playbackEndMeasure = playbackEndMeasure,
            playerState = playerState,
            canUndo = practiceVersion?.canUndo ?: session.canUndo,
            canRedo = practiceVersion?.canRedo ?: session.canRedo,
            showSource = showSource,
            onToggleSource = onToggleSource,
            onUndo = onUndo,
            onRedo = onRedo,
            onOpenPlaybackSettings = onOpenPlaybackSettings,
            onTogglePlayback = onTogglePlayback,
            onStopPlayback = onStopPlayback,
            onOpenCorrection = onOpenCorrection,
            onOpenGuidance = onOpenGuidance,
            practiceVersion = practiceVersion?.version,
            onOpenPracticeVersions = onOpenPracticeVersions,
            onExitPracticeVersion = onExitPracticeVersion,
            onOpenMidiPractice = onOpenMidiPractice,
            mainExportBusy = mainExportBusy,
            mainExportMessage = mainExportMessage,
            mainExportFailed = mainExportFailed,
            onExportCurrent = onExportCurrent,
        )
    }
}

@Composable
private fun ScoreMetadata(
    summary: MusicXmlSummary,
    revisionNumber: Int,
    practiceVersion: PracticeVersion?,
    hasSource: Boolean,
    showSource: Boolean,
    onToggleSource: () -> Unit,
    onChooseAnotherScore: () -> Unit,
    onOpenHistory: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(practiceVersion?.title ?: summary.title, style = MaterialTheme.typography.titleSmall)
        val timeSignature = listOfNotNull(summary.beats, summary.beatType)
            .takeIf { it.size == 2 }
            ?.joinToString("/")
            ?: "未识别拍号"
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (practiceVersion == null) {
                    "${summary.measureCount} 小节 · $timeSignature · 修订 $revisionNumber"
                } else {
                    "${summary.measureCount} 小节 · ${practiceVersionStatusLabel(practiceVersion.status)} · 修订 $revisionNumber"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.clickable(onClick = onOpenHistory),
            )
            if (hasSource) {
                TextButton(onClick = onToggleSource) { Text(if (showSource) "练习谱" else "原谱") }
            }
            TextButton(onClick = onChooseAnotherScore) { Text("换谱") }
        }
    }
}

@Composable
private fun OriginalScoreView(source: SourceScorePage, modifier: Modifier = Modifier) {
    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surfaceVariant) {
        ScoreRenderer(
            score = source.score,
            page = (source.page?.sourceIndex ?: 0) + 1,
            pageRelativePath = source.page?.relativePath,
            modifier = Modifier.fillMaxSize().padding(8.dp),
        )
    }
}

@Composable
private fun MeasureSelector(
    measureCount: Int,
    selectedMeasure: Int,
    playbackMeasure: Int?,
    onSelectMeasure: (Int) -> Unit,
) {
    LazyRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items((1..measureCount).toList(), key = { it }) { measure ->
            AssistChip(
                onClick = { onSelectMeasure(measure) },
                label = { Text("第 $measure 小节") },
                leadingIcon = when (measure) {
                    playbackMeasure -> ({ Text("▶") })
                    selectedMeasure -> ({ Text("●") })
                    else -> null
                },
            )
        }
    }
}

@Composable
@OptIn(ExperimentalContracts::class, ExperimentalUnsignedTypes::class)
private fun ScoreRenderer(
    xml: String,
    score: ScoreIr,
    selectedMeasure: Int,
    playbackMeasure: Int?,
    playbackController: AlphaTabPlaybackController,
    modifier: Modifier = Modifier,
) {
    var renderState by remember(xml) { mutableStateOf<ScoreRenderState>(ScoreRenderState.Loading) }
    Box(modifier = modifier.fillMaxSize()) {
        key(xml) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { viewContext ->
                    AlphaTabView(viewContext, null).apply {
                        var loadStarted = false
                        settings.player.enableCursor = true
                        settings.player.enableElementHighlighting = true
                        settings.player.configureGpianoScrolling()
                        api.customScrollHandler = object : IScrollHandler {
                            override fun forceScrollTo(currentBeatBounds: BeatBounds) = Unit

                            override fun onBeatCursorUpdating(
                                startBeat: BeatBounds,
                                endBeat: BeatBounds?,
                                cursorMode: MidiTickLookupFindBeatResultCursorMode,
                                actualBeatCursorStartX: Double,
                                actualBeatCursorEndX: Double,
                                actualBeatCursorTransitionDuration: Double,
                            ) = Unit

                            override fun close() = Unit
                        }
                        settings.notation.elements.set(NotationElement.TrackNames, false)
                        settings.notation.elements.set(NotationElement.ScoreTitle, false)
                        settings.notation.elements.set(NotationElement.ScoreSubTitle, false)
                        settings.notation.elements.set(NotationElement.ScoreArtist, false)
                        settings.notation.elements.set(NotationElement.ScoreAlbum, false)
                        settings.notation.elements.set(NotationElement.ScoreWords, false)
                        settings.notation.elements.set(NotationElement.ScoreMusic, false)
                        settings.notation.elements.set(NotationElement.ScoreWordsAndMusic, false)
                        settings.notation.elements.set(NotationElement.ScoreCopyright, false)
                        settings.display.padding = DoubleList(0.0, 0.0, 0.0, 0.0)
                        playbackController.attach(
                            this,
                            score.parts.associate { part ->
                                part.index to (
                                    part.measures.asSequence()
                                        .flatMap { it.events.asSequence() }
                                        .map(ScoreEventIr::hand)
                                        .firstOrNull { it != ScoreHand.Unknown }
                                        ?: ScoreHand.Unknown
                                    )
                            },
                        )
                        api.renderStarted.on { isResize ->
                            Log.d("GpianoAlphaTab", "renderStarted: resize=$isResize, view=${width}x${height}")
                        }
                        api.renderFinished.on { result ->
                            Log.d(
                                "GpianoAlphaTab",
                                "renderFinished: ${result.totalWidth}x${result.totalHeight}, " +
                                    "part=${result.width}x${result.height}",
                            )
                            post {
                                renderState = ScoreRenderState.Ready
                                stabilizeGpianoLazyRendering()
                                playbackController.markScoreRendered()
                                scrollToMeasure(tag as? Int ?: 1)
                            }
                        }
                        api.error.on { error ->
                            Log.e("GpianoAlphaTab", "render error", error)
                            post {
                                renderState = ScoreRenderState.Failed(error.message ?: "渲染器无法处理此乐谱")
                            }
                        }
                        addOnLayoutChangeListener { _, _, _, right, bottom, _, _, _, _ ->
                            if (!loadStarted && right > 0 && bottom > 0) {
                                loadStarted = true
                                post {
                                    runCatching {
                                        val bytes = xml.toByteArray(Charsets.UTF_8)
                                        val uint8Constructor = Uint8Array::class.java.declaredConstructors.first {
                                            it.parameterTypes.size == 2 && it.parameterTypes[0] == ByteArray::class.java
                                        }.apply { isAccessible = true }
                                        val score = ScoreLoader.loadScoreFromBytes(
                                            uint8Constructor.newInstance(bytes, null) as Uint8Array,
                                            api.settings,
                                        )
                                        val normalizedBars = AlphaTabPlaybackTimeline
                                            .normalizeOverfullMeasures(score)
                                        val trackIndexes = DoubleList()
                                        for (track in score.tracks) trackIndexes.push(track.index)
                                        Log.d(
                                            "GpianoAlphaTab",
                                            "parsed score: tracks=${score.tracks.count()}, " +
                                                "normalizedOverfullBars=$normalizedBars",
                                        )
                                        api.renderScore(score, trackIndexes)
                                    }.onFailure { error ->
                                        Log.e("GpianoAlphaTab", "MusicXML parse failed", error)
                                        renderState = ScoreRenderState.Failed(
                                            error.message ?: "无法解析 MusicXML",
                                        )
                                    }
                                }
                            }
                        }
                    }
                },
                update = { view ->
                    val targetMeasure = playbackMeasure ?: selectedMeasure
                    if (view.tag != targetMeasure) {
                        view.tag = targetMeasure
                        if (renderState is ScoreRenderState.Ready) view.scrollToMeasure(targetMeasure)
                    }
                },
                onRelease = { view -> playbackController.detach(view) },
            )
        }
        when (val current = renderState) {
            ScoreRenderState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            is ScoreRenderState.Failed -> Card(
                modifier = Modifier.align(Alignment.Center).padding(20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
            ) {
                Text("无法渲染结构化乐谱：${current.message}", modifier = Modifier.padding(16.dp))
            }
            ScoreRenderState.Ready -> Unit
        }
    }
}

@Composable
private fun SelectedMeasureStatus(
    selectedMeasure: Int,
    eventCount: Int,
    statusMessage: String?,
    playbackEndMeasure: Int,
    playerState: ScorePlayerUiState,
    canUndo: Boolean,
    canRedo: Boolean,
    showSource: Boolean,
    onToggleSource: () -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onOpenPlaybackSettings: () -> Unit,
    onTogglePlayback: () -> Unit,
    onStopPlayback: () -> Unit,
    onOpenCorrection: () -> Unit,
    onOpenGuidance: () -> Unit,
    practiceVersion: PracticeVersion?,
    onOpenPracticeVersions: () -> Unit,
    onExitPracticeVersion: () -> Unit,
    onOpenMidiPractice: () -> Unit,
    mainExportBusy: Boolean,
    mainExportMessage: String?,
    mainExportFailed: Boolean,
    onExportCurrent: () -> Unit,
) {
    Surface(tonalElevation = 2.dp, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (showSource) {
                Text(
                    "原谱对照 · 第 $selectedMeasure 小节",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(onClick = onToggleSource) { Text("返回练习谱") }
                return@Row
            }
            if (practiceVersion != null) {
                Text(
                    statusMessage ?: playerState.error ?: playerState.currentMeasure?.let { "派生版播放第 $it 小节" }
                    ?: "${practiceVersionStatusLabel(practiceVersion.status)} · 第 $selectedMeasure 小节",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(modifier = Modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                    if (canUndo) TextButton(onClick = onUndo) { Text("撤销") }
                    if (canRedo) TextButton(onClick = onRedo) { Text("重做") }
                    TextButton(onClick = onOpenCorrection) { Text("校正") }
                    TextButton(onClick = onOpenPracticeVersions) { Text("差异") }
                    TextButton(onClick = onExitPracticeVersion) { Text("主谱") }
                    TextButton(onClick = onOpenGuidance) { Text("指导") }
                    TextButton(onClick = onOpenMidiPractice) { Text("跟弹") }
                    TextButton(onClick = onOpenPlaybackSettings) {
                        Text(if (selectedMeasure == playbackEndMeasure) "范围" else "$selectedMeasure–$playbackEndMeasure")
                    }
                    if (playerState.phase == ScorePlayerPhase.Playing || playerState.phase == ScorePlayerPhase.Paused) {
                        TextButton(onClick = onStopPlayback) { Text("停止") }
                    }
                    Button(onClick = onTogglePlayback, enabled = playerState.phase != ScorePlayerPhase.Preparing) {
                        Text(
                            when (playerState.phase) {
                                ScorePlayerPhase.Preparing -> "准备"
                                ScorePlayerPhase.Playing -> "暂停"
                                ScorePlayerPhase.Paused -> "继续"
                                else -> "试听"
                            },
                        )
                    }
                }
                return@Row
            }
            Text(
                mainExportMessage ?: statusMessage ?: playerState.error ?: playerState.currentMeasure?.let { "播放第 $it 小节" }
                ?: "第 $selectedMeasure 小节 · $eventCount 个事件",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall,
                color = if (mainExportFailed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (canUndo) TextButton(onClick = onUndo) { Text("撤销") }
                if (canRedo) TextButton(onClick = onRedo) { Text("重做") }
                TextButton(onClick = onOpenGuidance) { Text("指导") }
                TextButton(onClick = onOpenPracticeVersions) { Text("版本") }
                TextButton(onClick = onOpenMidiPractice) { Text("跟弹") }
                TextButton(onClick = onExportCurrent, enabled = !mainExportBusy) {
                    Text(if (mainExportBusy) "导出中" else "导出")
                }
                TextButton(onClick = onOpenPlaybackSettings) {
                    Text(if (selectedMeasure == playbackEndMeasure) "范围" else "$selectedMeasure–$playbackEndMeasure")
                }
                if (playerState.phase == ScorePlayerPhase.Playing || playerState.phase == ScorePlayerPhase.Paused) {
                    TextButton(onClick = onStopPlayback) { Text("停止") }
                } else {
                    TextButton(onClick = onOpenCorrection) { Text("校正") }
                }
                Button(
                    onClick = onTogglePlayback,
                    enabled = playerState.phase != ScorePlayerPhase.Preparing,
                ) {
                    Text(
                        when (playerState.phase) {
                            ScorePlayerPhase.Preparing -> "准备"
                            ScorePlayerPhase.Playing -> "暂停"
                            ScorePlayerPhase.Paused -> "继续"
                            else -> "试听"
                        },
                    )
                }
            }
        }
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun SimpleMessageSheet(title: String, message: String, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            Text(message, modifier = Modifier.padding(top = 8.dp))
            Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth().padding(top = 16.dp)) { Text("知道了") }
            Spacer(Modifier.height(20.dp))
        }
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun PracticeGuidanceSheet(
    analysis: PracticeAnalysis,
    onPreview: (RecommendedPlayback) -> Unit,
    onAskAi: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.84f)
                .padding(horizontal = 16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("练习指导", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "本地结构分析 · ${analysis.overview}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onAskAi) { Text("问 AI") }
                    TextButton(onClick = onDismiss) { Text("完成") }
                }
            }
            Text(
                "结论来自当前结构化草稿，并标出谱面依据。若原谱与练习谱不一致，请先校正再练习。",
                modifier = Modifier.padding(top = 6.dp, bottom = 8.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            LazyColumn(
                modifier = Modifier.fillMaxWidth().weight(1f),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(analysis.guidance, key = PracticeGuidance::id) { guidance ->
                    PracticeGuidanceCard(guidance, onPreview)
                }
                item { Spacer(Modifier.height(20.dp)) }
            }
        }
    }
}

@Composable
private fun PracticeGuidanceCard(
    guidance: PracticeGuidance,
    onPreview: (RecommendedPlayback) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(guidance.topic.displayName, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Text(guidance.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 2.dp))
            Text(guidance.explanation, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 6.dp))
            Surface(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.surfaceVariant,
            ) {
                Column(modifier = Modifier.padding(10.dp)) {
                    Text("谱面依据", style = MaterialTheme.typography.labelMedium)
                    Text(
                        guidance.evidence.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (guidance.evidence.measureIndexes.isNotEmpty()) {
                        Text(
                            "对应第 ${guidance.evidence.measureIndexes.joinToString("、")} 小节 · ${guidance.evidence.eventIds.size} 个关联事件",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            Text("怎么练", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 10.dp))
            guidance.instructions.forEach { instruction ->
                Text(
                    "${instruction.order}. ${instruction.text}",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            guidance.recommendedPlayback?.let { recommendation ->
                Button(
                    onClick = { onPreview(recommendation) },
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                ) {
                    Text(
                        "${(recommendation.speed * 100).roundToInt()}% · ${playbackHandLabel(recommendation.hand)}" +
                            if (recommendation.looping) " · 循环试听" else " · 试听",
                    )
                }
            }
        }
    }
}

private fun playbackHandLabel(hand: PlaybackHand): String = when (hand) {
    PlaybackHand.Both -> "双手"
    PlaybackHand.Right -> "右手"
    PlaybackHand.Left -> "左手"
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun PlaybackSettingsSheet(
    score: ScoreIr,
    startMeasure: Int,
    endMeasure: Int,
    speed: Double,
    hand: PlaybackHand,
    looping: Boolean,
    onRangeChange: (Int, Int) -> Unit,
    onSpeedChange: (Double) -> Unit,
    onHandChange: (PlaybackHand) -> Unit,
    onLoopingChange: (Boolean) -> Unit,
    midiExportBusy: Boolean,
    midiExportMessage: String?,
    midiExportFailed: Boolean,
    onExportMidi: (PlaybackPlan) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val plan = remember(score, startMeasure, endMeasure, speed, hand, looping) {
        runCatching {
            PlaybackPlanCompiler.compile(
                score,
                PlaybackSelection(startMeasure, endMeasure, hand, speed, looping),
            )
        }.getOrNull()
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.82f)
                .padding(horizontal = 16.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("片段试听", style = MaterialTheme.typography.titleLarge)
                    Text(
                        plan?.let { "约 ${formatDuration(it.durationMillis)} · ${it.events.size} 个发音事件" }
                            ?: "当前范围无法生成播放时间轴",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = onDismiss) { Text("完成") }
            }

            Text("小节范围", modifier = Modifier.padding(top = 14.dp), style = MaterialTheme.typography.titleSmall)
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("从第 $startMeasure 小节到第 $endMeasure 小节", modifier = Modifier.weight(1f))
                TextButton(onClick = { onRangeChange(startMeasure, startMeasure) }) { Text("当前小节") }
                TextButton(onClick = { onRangeChange(1, score.measureCount) }) { Text("全篇") }
            }
            RangeMeasurePicker(
                label = "起点",
                measures = 1..score.measureCount,
                selected = startMeasure,
                onSelect = { start -> onRangeChange(start, endMeasure.coerceAtLeast(start)) },
            )
            RangeMeasurePicker(
                label = "终点",
                measures = startMeasure..score.measureCount,
                selected = endMeasure,
                onSelect = { end -> onRangeChange(startMeasure, end) },
            )

            Text("速度", modifier = Modifier.padding(top = 10.dp), style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(0.5 to "50%", 0.75 to "75%", 1.0 to "原速").forEach { (value, label) ->
                    FilterChip(
                        selected = speed == value,
                        onClick = { onSpeedChange(value) },
                        label = { Text(label) },
                    )
                }
            }

            Text("声部", modifier = Modifier.padding(top = 10.dp), style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(
                    PlaybackHand.Both to "双手",
                    PlaybackHand.Right to "右手",
                    PlaybackHand.Left to "左手",
                ).forEach { (value, label) ->
                    FilterChip(
                        selected = hand == value,
                        onClick = { onHandChange(value) },
                        label = { Text(label) },
                    )
                }
                FilterChip(
                    selected = looping,
                    onClick = { onLoopingChange(!looping) },
                    label = { Text("循环") },
                )
            }
            Text(
                "可直接选择起止小节；试听时会使用谱面游标跟随当前拍位。",
                modifier = Modifier.padding(top = 12.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(
                onClick = { plan?.let(onExportMidi) },
                enabled = plan != null && plan.events.isNotEmpty() && !midiExportBusy,
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            ) {
                if (midiExportBusy) {
                    CircularProgressIndicator(modifier = Modifier.padding(end = 8.dp), strokeWidth = 2.dp)
                }
                Text(if (midiExportBusy) "正在导出…" else "导出当前片段为 MIDI")
            }
            midiExportMessage?.let { message ->
                Text(
                    message,
                    modifier = Modifier.padding(top = 8.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (midiExportFailed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                )
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

private fun formatDuration(durationMillis: Long): String {
    val totalSeconds = (durationMillis / 1_000).coerceAtLeast(1)
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return if (minutes > 0) "$minutes 分 ${seconds} 秒" else "$seconds 秒"
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun CorrectionSheet(
    score: ScoreIr,
    measureIndex: Int,
    revisionNumber: Int,
    selectedEventId: String?,
    editInProgress: Boolean,
    error: String?,
    onSelectEvent: (String) -> Unit,
    onApplyOperation: (CorrectionOperation) -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onOpenHistory: () -> Unit,
    canUndo: Boolean,
    canRedo: Boolean,
    onDismiss: () -> Unit,
) {
    val events = remember(score, measureIndex) { score.eventsInMeasure(measureIndex) }
    val selectedEvent = events.firstOrNull { it.id == selectedEventId }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.75f)
                .padding(horizontal = 16.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("校正第 $measureIndex 小节", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "修订 $revisionNumber · 校正音高、时值、休止符或延音线",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = onOpenHistory, enabled = !editInProgress) { Text("历史") }
                if (canUndo) TextButton(onClick = onUndo, enabled = !editInProgress) { Text("撤销") }
                if (canRedo) TextButton(onClick = onRedo, enabled = !editInProgress) { Text("重做") }
                TextButton(onClick = onDismiss, enabled = !editInProgress) { Text("完成") }
            }

            error?.let {
                Text(
                    it,
                    modifier = Modifier.padding(vertical = 8.dp),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            Text(
                "小节事件",
                modifier = Modifier.padding(top = 12.dp, bottom = 6.dp),
                style = MaterialTheme.typography.titleSmall,
            )
            LazyRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(events, key = ScoreEventIr::id) { event ->
                    CorrectionEventChip(
                        event = event,
                        selected = event.id == selectedEventId,
                        enabled = !editInProgress,
                        onClick = { onSelectEvent(event.id) },
                    )
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
            if (selectedEvent != null) {
                EventCorrectionEditor(
                    score = score,
                    event = selectedEvent,
                    editInProgress = editInProgress,
                    onApplyOperation = onApplyOperation,
                )
            } else {
                Text(
                    if (events.isEmpty()) "这个小节没有可校正事件" else "请选择一个事件",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun CorrectionEventChip(
    event: ScoreEventIr,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val hand = when (event.hand) {
        ScoreHand.Right -> "右手"
        ScoreHand.Left -> "左手"
        ScoreHand.Unknown -> "未分手"
    }
    FilterChip(
        selected = selected,
        onClick = onClick,
        enabled = enabled,
        label = {
            Column(modifier = Modifier.padding(vertical = 2.dp)) {
                Text(event.pitch?.displayName ?: "休止符", style = MaterialTheme.typography.titleSmall)
                Text(
                    "$hand · ${event.onsetDivisions}" + if (event.isChordTone) " · 和弦" else "",
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        },
    )
}

@Composable
private fun EventCorrectionEditor(
    score: ScoreIr,
    event: ScoreEventIr,
    editInProgress: Boolean,
    onApplyOperation: (CorrectionOperation) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
        if (event.pitch != null) {
            PitchCorrectionEditor(
                event = event,
                editInProgress = editInProgress,
                onApply = { onApplyOperation(CorrectionOperation.ChangePitch(event.id, it)) },
            )
            TextButton(
                onClick = { onApplyOperation(CorrectionOperation.ChangeRest(event.id, makeRest = true)) },
                enabled = !editInProgress,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("将这个独立音符改为休止符") }
        } else {
            RestToNoteEditor(
                event = event,
                editInProgress = editInProgress,
                onApply = {
                    onApplyOperation(
                        CorrectionOperation.ChangeRest(event.id, makeRest = false, pitchWhenNote = it),
                    )
                },
            )
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
        DurationCorrectionEditor(
            score = score,
            event = event,
            editInProgress = editInProgress,
            onApply = { onApplyOperation(CorrectionOperation.ChangeDuration(event.id, it)) },
        )

        if (event.pitch != null) {
            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
            Text("延音线", style = MaterialTheme.typography.titleSmall)
            Text(
                if (event.tieStart) "当前音符与下一发音位置相连" else "仅可连接到同声部下一发音位置中的相同音高",
                modifier = Modifier.padding(top = 2.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(
                onClick = {
                    onApplyOperation(CorrectionOperation.SetTieToNext(event.id, enabled = !event.tieStart))
                },
                enabled = !editInProgress,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (event.tieStart) "移除与下一音的延音线" else "连接到下一同音") }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun DurationCorrectionEditor(
    score: ScoreIr,
    event: ScoreEventIr,
    editInProgress: Boolean,
    onApply: (MusicalDuration) -> Unit,
) {
    val measure = score.parts[event.partIndex].measures[event.measureIndex - 1]
    val current = MusicalDuration.from(event)
    val candidates = remember(event.id, event.noteType, event.dots, event.tupletActualNotes, event.tupletNormalNotes) {
        (
            MusicalDuration.commonValues +
                listOf(
                    MusicalDuration("half", dots = 1),
                    MusicalDuration("quarter", dots = 1),
                    MusicalDuration("eighth", dots = 1),
                    MusicalDuration("quarter", actualNotes = 3, normalNotes = 2),
                    MusicalDuration("eighth", actualNotes = 3, normalNotes = 2),
                    MusicalDuration("16th", actualNotes = 3, normalNotes = 2),
                ) + listOfNotNull(current)
            ).distinct().filter { runCatching { it.toDivisions(measure.divisions) }.isSuccess }
    }
    var draft by remember(event.id, event.durationDivisions) {
        mutableStateOf(current ?: candidates.first())
    }
    Text("时值", style = MaterialTheme.typography.titleSmall)
    Text(
        "当前 ${current?.displayName ?: event.noteType ?: "未知"} · ${event.durationDivisions} divisions",
        modifier = Modifier.padding(top = 2.dp, bottom = 6.dp),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(candidates, key = { "${it.noteType}:${it.dots}:${it.actualNotes}:${it.normalNotes}" }) { value ->
            FilterChip(
                selected = draft == value,
                onClick = { draft = value },
                enabled = !editInProgress,
                label = { Text(value.displayName) },
            )
        }
    }
    Button(
        onClick = { onApply(draft) },
        enabled = !editInProgress && runCatching { draft.toDivisions(measure.divisions) }.getOrNull() != event.durationDivisions,
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
    ) { Text("应用 ${draft.displayName} 并重新渲染") }
}

@Composable
private fun RestToNoteEditor(
    event: ScoreEventIr,
    editInProgress: Boolean,
    onApply: (ScorePitch) -> Unit,
) {
    var draft by remember(event.id) { mutableStateOf(ScorePitch('C', 0, 4)) }
    Text("休止符", style = MaterialTheme.typography.titleSmall)
    Text(
        "选择要恢复的音高",
        modifier = Modifier.padding(top = 2.dp, bottom = 8.dp),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    PitchSelector(draft, !editInProgress) { draft = it }
    Button(
        onClick = { onApply(draft) },
        enabled = !editInProgress && draft.midi in 0..127,
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
    ) { Text("改为 ${draft.displayName} 音符并重新渲染") }
}

@Composable
private fun PitchCorrectionEditor(
    event: ScoreEventIr,
    editInProgress: Boolean,
    onApply: (ScorePitch) -> Unit,
) {
    val original = requireNotNull(event.pitch)
    var draft by remember(event.id, original) { mutableStateOf(original) }
    Text("音高", style = MaterialTheme.typography.titleSmall)
    Text(
        "当前 ${original.displayName} · MIDI ${original.midi}",
        modifier = Modifier.padding(top = 2.dp, bottom = 8.dp),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    PitchSelector(draft, !editInProgress) { draft = it }
    Button(
        onClick = { onApply(draft) },
        enabled = !editInProgress && draft != original && draft.midi in 0..127,
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
    ) {
        if (editInProgress) {
            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
        } else {
            Text("应用 ${draft.displayName} 并重新渲染")
        }
    }
}

@Composable
private fun PitchSelector(
    pitch: ScorePitch,
    enabled: Boolean,
    onChange: (ScorePitch) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        listOf('C', 'D', 'E', 'F', 'G', 'A', 'B').forEach { step ->
            FilterChip(
                selected = pitch.step == step,
                onClick = { onChange(pitch.copy(step = step)) },
                label = { Text(step.toString()) },
                enabled = enabled,
            )
        }
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        listOf(-1 to "♭", 0 to "♮", 1 to "♯").forEach { (alter, label) ->
            FilterChip(
                selected = pitch.alter == alter,
                onClick = { onChange(pitch.copy(alter = alter)) },
                label = { Text(label) },
                enabled = enabled,
            )
        }
        Spacer(Modifier.weight(1f))
        TextButton(
            onClick = { onChange(pitch.copy(octave = (pitch.octave - 1).coerceAtLeast(0))) },
            enabled = enabled && pitch.octave > 0,
        ) { Text("−") }
        Text("${pitch.octave} 组", style = MaterialTheme.typography.titleSmall)
        TextButton(
            onClick = { onChange(pitch.copy(octave = (pitch.octave + 1).coerceAtMost(9))) },
            enabled = enabled && pitch.octave < 9,
        ) { Text("+") }
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun RevisionHistorySheet(
    session: PersistentScoreSession,
    editInProgress: Boolean,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.72f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("修订历史", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "切换旧版本后继续修改会建立新的分支，原修订不会删除。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = onDismiss, enabled = !editInProgress) { Text("完成") }
            }
            session.revisions.forEach { revision ->
                val current = revision.id == session.revision.id
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                        .clickable(enabled = !current && !editInProgress) { onSelect(revision.id) },
                    shape = MaterialTheme.shapes.medium,
                    tonalElevation = if (current) 5.dp else 1.dp,
                ) {
                    Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "修订 ${revision.revisionNumber}${if (current) " · 当前" else ""}",
                                style = MaterialTheme.typography.titleSmall,
                            )
                            Text(
                                revisionDescription(revision.kind, revision.operationJson),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (!current) Text("切换", color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun PracticeVersionRevisionHistorySheet(
    session: PracticeVersionDocument,
    editInProgress: Boolean,
    onSelect: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.72f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("练习版本修订历史", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "这里只切换当前派生版本；主谱和其他练习版本不会改变。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = onDismiss, enabled = !editInProgress) { Text("完成") }
            }

            val baseIsCurrent = session.revision == null
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
                    .clickable(enabled = !baseIsCurrent && !editInProgress) { onSelect(null) },
                shape = MaterialTheme.shapes.medium,
                tonalElevation = if (baseIsCurrent) 5.dp else 1.dp,
            ) {
                Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "修订 0 · 基础候选${if (baseIsCurrent) " · 当前" else ""}",
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Text(
                            "由 ${session.plan.preset.displayName} 规则生成的稳定恢复点",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (!baseIsCurrent) Text("切换", color = MaterialTheme.colorScheme.primary)
                }
            }

            session.revisions.forEach { revision: PracticeVersionRevision ->
                val current = revision.id == session.revision?.id
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                        .clickable(enabled = !current && !editInProgress) { onSelect(revision.id) },
                    shape = MaterialTheme.shapes.medium,
                    tonalElevation = if (current) 5.dp else 1.dp,
                ) {
                    Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "修订 ${revision.revisionNumber}${if (current) " · 当前" else ""}",
                                style = MaterialTheme.typography.titleSmall,
                            )
                            Text(
                                revisionDescription(revision.kind, revision.operationJson),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (!current) Text("切换", color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

private fun revisionDescription(kind: String, operationJson: String?): String {
    val operation = runCatching { operationJson?.let(::JSONObject) }.getOrNull()
    return when (operation?.optString("type")) {
        "changePitch" -> "修改音高 · ${operation.optString("eventId")}"
        "changeDuration" -> "修改时值 · ${operation.optString("eventId")}"
        "changeRest" -> if (operation.optBoolean("makeRest")) "改为休止符" else "改为音符"
        "setTieToNext" -> if (operation.optBoolean("enabled")) "添加延音线" else "移除延音线"
        else -> when (kind) {
            "omr" -> "OMR 识别草稿"
            "source" -> "导入的 MusicXML"
            else -> "用户修订"
        }
    }
}

@OptIn(ExperimentalContracts::class, ExperimentalUnsignedTypes::class)
private fun AlphaTabView.scrollToMeasure(measure: Int) {
    val bounds = api.boundsLookup?.findMasterBarByIndex((measure - 1).toDouble()) ?: return
    val verticalScroll = findViewById<ScrollView>(AlphaTabR.id.innerScroll) ?: return
    val horizontalScroll = findViewById<HorizontalScrollView>(AlphaTabR.id.outerScroll) ?: return
    verticalScroll.post {
        Log.d(
            "GpianoAlphaTab",
            "measure=$measure visual=${bounds.visualBounds.y}/${bounds.visualBounds.h}, " +
                "real=${bounds.realBounds.y}/${bounds.realBounds.h}, " +
                "line=${bounds.lineAlignedBounds.y}/${bounds.lineAlignedBounds.h}, " +
                "scroll=${horizontalScroll.scrollX}/${verticalScroll.scrollY}, " +
                "viewport=${horizontalScroll.width}x${verticalScroll.height}",
        )
        val density = resources.displayMetrics.density
        val targetX = (bounds.visualBounds.x * density)
            .roundToInt()
            .minus((32 * density).roundToInt())
            .coerceAtLeast(0)
        val targetY = (bounds.visualBounds.y * resources.displayMetrics.density)
            .roundToInt()
            .minus((32 * resources.displayMetrics.density).roundToInt())
            .coerceAtLeast(0)
        horizontalScroll.scrollTo(targetX, 0)
        verticalScroll.scrollTo(0, targetY)
    }
}

@OptIn(ExperimentalContracts::class, ExperimentalUnsignedTypes::class)
private fun AlphaTabView.stabilizeGpianoLazyRendering() {
    val renderSurface = findViewById<View>(AlphaTabR.id.renderSurface) ?: return
    val alphaTabScrollListener = renderSurface as? View.OnScrollChangeListener ?: return
    val verticalScroll = findViewById<ScrollView>(AlphaTabR.id.innerScroll) ?: return
    val horizontalScroll = findViewById<HorizontalScrollView>(AlphaTabR.id.outerScroll) ?: return
    val restoreVisibleParts = Runnable {
        renderSurface.requestLayout()
        renderSurface.postInvalidate()
    }
    verticalScroll.setOnScrollChangeListener { view, x, y, oldX, oldY ->
        alphaTabScrollListener.onScrollChange(view, x, y, oldX, oldY)
        if (y < oldY) {
            renderSurface.removeCallbacks(restoreVisibleParts)
            renderSurface.postDelayed(restoreVisibleParts, 80L)
        }
    }
    horizontalScroll.setOnScrollChangeListener { view, x, y, oldX, oldY ->
        alphaTabScrollListener.onScrollChange(view, x, y, oldX, oldY)
        if (x < oldX) {
            renderSurface.removeCallbacks(restoreVisibleParts)
            renderSurface.postDelayed(restoreVisibleParts, 80L)
        }
    }
}

@Composable
private fun RangeMeasurePicker(
    label: String,
    measures: IntRange,
    selected: Int,
    onSelect: (Int) -> Unit,
) {
    Text(label, modifier = Modifier.padding(top = 8.dp), style = MaterialTheme.typography.labelMedium)
    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        items(measures.toList(), key = { "$label-$it" }) { measure ->
            FilterChip(
                selected = measure == selected,
                onClick = { onSelect(measure) },
                label = { Text(measure.toString()) },
            )
        }
    }
}
