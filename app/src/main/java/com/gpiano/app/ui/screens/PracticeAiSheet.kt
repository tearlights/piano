package com.gpiano.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.gpiano.app.ai.PracticeAiClient
import com.gpiano.app.ai.PracticeAiResult
import com.gpiano.app.ai.PracticeAiSettingsStore
import com.gpiano.app.scoreworkspace.PlaybackSelection
import com.gpiano.app.scoreworkspace.PracticeAnalysis
import com.gpiano.app.scoreworkspace.PracticeVersionPreset
import com.gpiano.app.scoreworkspace.RecommendedPlayback
import com.gpiano.app.scoreworkspace.ScoreIr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun PracticeAiSheet(
    score: ScoreIr,
    analysis: PracticeAnalysis,
    selection: PlaybackSelection,
    onPreview: (RecommendedPlayback) -> Unit,
    onCreatePracticeVersion: (suspend (PracticeVersionPreset) -> Unit)?,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current.applicationContext
    val settings = remember { PracticeAiSettingsStore(context).load() }
    val client = remember { PracticeAiClient() }
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var question by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var result by remember { mutableStateOf<PracticeAiResult?>(null) }
    var versionBusy by remember { mutableStateOf(false) }
    var versionError by remember { mutableStateOf<String?>(null) }
    val range = if (analysis.fromMeasure == analysis.toMeasure) {
        "第 ${analysis.fromMeasure} 小节"
    } else {
        "第 ${analysis.fromMeasure}–${analysis.toMeasure} 小节"
    }

    ModalBottomSheet(
        onDismissRequest = { if (!busy && !versionBusy) onDismiss() },
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.9f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("询问 AI", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "$range · 以本地结构证据为边界",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = onDismiss, enabled = !busy && !versionBusy) { Text("完成") }
            }

            if (settings == null) {
                Card(modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text("尚未连接 AI 练习服务", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "请先在“设置”中填写自托管 practice AI companion 的地址和令牌。本地指导、播放和校正仍可继续使用。",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                }
            } else if (result == null) {
                Text(
                    "你可以问节奏、连线、音高、左右手配合、落键或这一段该怎样安排练习。",
                    modifier = Modifier.padding(top = 12.dp),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                    listOf("这一段先练什么？", "节奏为什么容易错？").forEach { example ->
                        AssistChip(onClick = { question = example }, label = { Text(example) })
                    }
                }
                OutlinedTextField(
                    value = question,
                    onValueChange = { if (it.length <= 800) question = it },
                    label = { Text("关于当前选段的问题") },
                    minLines = 3,
                    maxLines = 6,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                )
                Card(modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text("本次会发送", style = MaterialTheme.typography.labelLarge)
                        Text(
                            "$range 的结构事件摘要、当前${handLabel(selection)}、本地分析证据和你的问题；不发送原谱图片或完整 MusicXML。",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
                Button(
                    onClick = {
                        busy = true
                        error = null
                        scope.launch {
                            runCatching {
                                withContext(Dispatchers.IO) {
                                    client.analyze(settings, question, score, analysis, selection)
                                }
                            }.onSuccess { result = it }
                                .onFailure { error = it.message ?: "AI 练习服务暂时不可用" }
                            busy = false
                        }
                    },
                    enabled = !busy && question.isNotBlank(),
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                ) {
                    if (busy) CircularProgressIndicator(modifier = Modifier.padding(end = 8.dp), strokeWidth = 2.dp)
                    Text(if (busy) "正在分析选段…" else "同意发送该选段并提问")
                }
                error?.let { message ->
                    Text(
                        "$message。可以修改问题后重试；本地练习功能不受影响。",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 10.dp),
                    )
                }
            } else {
                PracticeAiResultContent(
                    result = requireNotNull(result),
                    onPreview = onPreview,
                    creatingVersion = versionBusy,
                    versionError = versionError,
                    onCreatePracticeVersion = onCreatePracticeVersion?.let { create ->
                        { preset ->
                            versionBusy = true
                            versionError = null
                            scope.launch {
                                runCatching { create(preset) }
                                    .onFailure {
                                        versionError = it.message ?: "当前选段无法生成 AI 建议的练习版本"
                                    }
                                versionBusy = false
                            }
                        }
                    },
                    onAskAgain = {
                        result = null
                        error = null
                        versionError = null
                    },
                )
            }
            Spacer(Modifier.height(28.dp))
        }
    }
}

@Composable
private fun PracticeAiResultContent(
    result: PracticeAiResult,
    onPreview: (RecommendedPlayback) -> Unit,
    creatingVersion: Boolean,
    versionError: String?,
    onCreatePracticeVersion: ((PracticeVersionPreset) -> Unit)?,
    onAskAgain: () -> Unit,
) {
    HorizontalDivider(modifier = Modifier.padding(vertical = 14.dp))
    Text("结合当前选段的回答", style = MaterialTheme.typography.titleMedium)
    Text(result.answer, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp))
    Text("建议这样练", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 14.dp))
    result.practiceSteps.forEachIndexed { index, step ->
        Text("${index + 1}. $step", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 5.dp))
    }
    Text("谱面依据", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 14.dp))
    result.evidence.forEach { evidence ->
        Card(modifier = Modifier.fillMaxWidth().padding(top = 7.dp)) {
            Column(modifier = Modifier.padding(10.dp)) {
                Text(evidence.summary, style = MaterialTheme.typography.bodySmall)
                Text(
                    "第 ${evidence.measureIndexes.joinToString("、")} 小节 · ${evidence.eventIds.size} 个关联事件",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
    result.suggestedPlayback?.let { playback ->
        Button(onClick = { onPreview(playback) }, modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
            Text("按建议参数试听：${(playback.speed * 100).toInt()}% · ${playbackHandLabelForAi(playback)}")
        }
    }
    result.practiceVersionPreset?.let { preset ->
        if (onCreatePracticeVersion != null) {
            OutlinedButton(
                onClick = { onCreatePracticeVersion(preset) },
                enabled = !creatingVersion,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            ) {
                if (creatingVersion) {
                    CircularProgressIndicator(modifier = Modifier.padding(end = 8.dp), strokeWidth = 2.dp)
                }
                Text(if (creatingVersion) "正在生成候选…" else "生成${preset.displayName}并审阅差异")
            }
        } else {
            Text(
                "AI 建议了${preset.displayName}；请返回主谱后生成，避免在派生版本上重复改编。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 10.dp),
            )
        }
    }
    versionError?.let { message ->
        Text(
            "$message。主谱没有被修改；可调整选段后重试，或选择其他练习版本类型。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
    Text(
        "回答只能依据已发送的结构摘要。若结构化谱与原图不一致，请先校正再采用建议。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 12.dp),
    )
    TextButton(onClick = onAskAgain, modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) { Text("继续问当前选段") }
}

private fun handLabel(selection: PlaybackSelection): String = when (selection.hand) {
    com.gpiano.app.scoreworkspace.PlaybackHand.Both -> "双手范围"
    com.gpiano.app.scoreworkspace.PlaybackHand.Right -> "右手范围"
    com.gpiano.app.scoreworkspace.PlaybackHand.Left -> "左手范围"
}

private fun playbackHandLabelForAi(playback: RecommendedPlayback): String = when (playback.hand) {
    com.gpiano.app.scoreworkspace.PlaybackHand.Both -> "双手"
    com.gpiano.app.scoreworkspace.PlaybackHand.Right -> "右手"
    com.gpiano.app.scoreworkspace.PlaybackHand.Left -> "左手"
}
