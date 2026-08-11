package com.gpiano.app.ui.screens

import alphaTab.AlphaTabView
import alphaTab.NotationElement
import alphaTab.collections.DoubleList
import alphaTab.core.ecmaScript.Uint8Array
import alphaTab.importer.ScoreLoader
import android.util.Log
import android.widget.ScrollView
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.unit.dp
import com.gpiano.app.scoreworkspace.AssetMusicXmlSource
import com.gpiano.app.scoreworkspace.MusicXmlDocument
import com.gpiano.app.scoreworkspace.MusicXmlSummary
import net.alphatab.R as AlphaTabR
import kotlin.contracts.ExperimentalContracts
import kotlin.math.roundToInt

private sealed interface WorkspaceLoadState {
    data object Loading : WorkspaceLoadState
    data class Ready(val document: MusicXmlDocument) : WorkspaceLoadState
    data class Failed(val message: String) : WorkspaceLoadState
}

private sealed interface ScoreRenderState {
    data object Loading : ScoreRenderState
    data object Ready : ScoreRenderState
    data class Failed(val message: String) : ScoreRenderState
}

@Composable
fun StructuredScoreWorkspaceScreen(contentPadding: PaddingValues) {
    val context = LocalContext.current.applicationContext
    val source = remember { AssetMusicXmlSource(context) }
    var state by remember { mutableStateOf<WorkspaceLoadState>(WorkspaceLoadState.Loading) }
    var selectedMeasure by remember { mutableIntStateOf(1) }

    LaunchedEffect(source) {
        state = WorkspaceLoadState.Loading
        state = runCatching { source.load() }
            .fold(
                onSuccess = { WorkspaceLoadState.Ready(it) },
                onFailure = { WorkspaceLoadState.Failed(it.message ?: "无法读取测试乐谱") },
            )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding)
            .background(MaterialTheme.colorScheme.background),
    ) {
        when (val currentState = state) {
            WorkspaceLoadState.Loading -> LoadingWorkspace()
            is WorkspaceLoadState.Failed -> FailedWorkspace(currentState.message)
            is WorkspaceLoadState.Ready -> WorkspaceContent(
                document = currentState.document,
                selectedMeasure = selectedMeasure,
                onSelectMeasure = { selectedMeasure = it },
            )
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
    Card(modifier = Modifier.padding(20.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text("无法加载测试乐谱", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Text(message)
            Spacer(Modifier.height(12.dp))
            Text("原有琴谱阅读功能不受影响；请重新安装 Debug 构建或检查测试资源。", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun WorkspaceContent(
    document: MusicXmlDocument,
    selectedMeasure: Int,
    onSelectMeasure: (Int) -> Unit,
) {
    val summary = document.summary
    Column(modifier = Modifier.fillMaxSize()) {
        ScoreMetadata(summary = summary)
        MeasureSelector(
            measureCount = summary.measureCount,
            selectedMeasure = selectedMeasure,
            onSelectMeasure = onSelectMeasure,
        )
        HorizontalDivider()
        ScoreRenderer(
            xml = document.xml,
            selectedMeasure = selectedMeasure,
            modifier = Modifier.weight(1f),
        )
        HorizontalDivider()
        SelectedMeasureStatus(selectedMeasure = selectedMeasure, summary = summary)
    }
}

@Composable
private fun ScoreMetadata(summary: MusicXmlSummary) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(summary.title, style = MaterialTheme.typography.titleSmall)
        val timeSignature = listOfNotNull(summary.beats, summary.beatType).takeIf { it.size == 2 }?.joinToString("/") ?: "未识别拍号"
        Text(
            "${summary.measureCount} 小节 · $timeSignature",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun MeasureSelector(
    measureCount: Int,
    selectedMeasure: Int,
    onSelectMeasure: (Int) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        (1..measureCount).forEach { measure ->
            AssistChip(
                onClick = { onSelectMeasure(measure) },
                label = { Text("第 $measure 小节") },
                leadingIcon = if (measure == selectedMeasure) ({ Text("●") }) else null,
            )
        }
    }
}

@Composable
@OptIn(ExperimentalContracts::class, ExperimentalUnsignedTypes::class)
private fun ScoreRenderer(xml: String, selectedMeasure: Int, modifier: Modifier = Modifier) {
    var renderState by remember(xml) { mutableStateOf<ScoreRenderState>(ScoreRenderState.Loading) }
    Box(modifier = modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { viewContext ->
                AlphaTabView(viewContext, null).apply {
                var loadStarted = false
                settings.notation.elements.set(NotationElement.TrackNames, false)
                settings.notation.elements.set(NotationElement.ScoreTitle, false)
                settings.display.padding = DoubleList(0.0, 0.0, 0.0, 0.0)
                api.renderStarted.on { isResize ->
                    Log.d("GpianoAlphaTab", "renderStarted: resize=$isResize, view=${width}x${height}")
                }
                api.renderFinished.on { result ->
                    Log.d(
                        "GpianoAlphaTab",
                        "renderFinished: ${result.totalWidth}x${result.totalHeight}, part=${result.width}x${result.height}",
                    )
                    post {
                        renderState = ScoreRenderState.Ready
                        scrollToMeasure(tag as? Int ?: 1)
                    }
                }
                api.error.on { error ->
                    Log.e("GpianoAlphaTab", "render error", error)
                    post { renderState = ScoreRenderState.Failed(error.message ?: "渲染器无法处理此乐谱") }
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
                                val trackIndexes = DoubleList()
                                for (track in score.tracks) trackIndexes.push(track.index)
                                Log.d("GpianoAlphaTab", "parsed score: tracks=${score.tracks.count()}")
                                api.renderScore(score, trackIndexes)
                            }.onFailure { error ->
                                Log.e("GpianoAlphaTab", "MusicXML parse failed", error)
                                renderState = ScoreRenderState.Failed(error.message ?: "无法解析 MusicXML")
                            }
                        }
                    }
                }
                }
            },
            update = { view ->
                view.tag = selectedMeasure
                if (renderState is ScoreRenderState.Ready) view.scrollToMeasure(selectedMeasure)
            },
        )
        when (val state = renderState) {
            ScoreRenderState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            is ScoreRenderState.Failed -> Card(
                modifier = Modifier.align(Alignment.Center).padding(20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
            ) {
                Text("无法渲染结构化乐谱：${state.message}", modifier = Modifier.padding(16.dp))
            }
            ScoreRenderState.Ready -> Unit
        }
    }
}

@Composable
private fun SelectedMeasureStatus(selectedMeasure: Int, summary: MusicXmlSummary) {
    Surface(tonalElevation = 2.dp, modifier = Modifier.fillMaxWidth()) {
        Text(
            "当前：第 $selectedMeasure 小节 · ${summary.parts.size} 个声部",
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@OptIn(ExperimentalContracts::class, ExperimentalUnsignedTypes::class)
private fun AlphaTabView.scrollToMeasure(measure: Int) {
    val bounds = api.boundsLookup?.findMasterBarByIndex((measure - 1).toDouble()) ?: return
    val scrollView = findViewById<ScrollView>(AlphaTabR.id.innerScroll) ?: return
    scrollView.post {
        Log.d(
            "GpianoAlphaTab",
            "measure=$measure visual=${bounds.visualBounds.y}/${bounds.visualBounds.h}, " +
                "real=${bounds.realBounds.y}/${bounds.realBounds.h}, " +
                "line=${bounds.lineAlignedBounds.y}/${bounds.lineAlignedBounds.h}, " +
                "scroll=${scrollView.scrollY}, child=${scrollView.getChildAt(0)?.height}, viewport=${scrollView.height}",
        )
        val targetY = (bounds.visualBounds.y * resources.displayMetrics.density)
            .roundToInt()
            .minus((32 * resources.displayMetrics.density).roundToInt())
            .coerceAtLeast(0)
        scrollView.smoothScrollTo(0, targetY)
    }
}
