package com.gpiano.app.ui.screens

import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.gpiano.app.data.PracticeAttempt
import com.gpiano.app.data.PracticeAttemptInputKind
import com.gpiano.app.midi.MatchKind
import com.gpiano.app.midi.MidiCaptureUiState
import com.gpiano.app.midi.MidiConnectionState
import com.gpiano.app.midi.MidiPracticeController
import com.gpiano.app.midi.PerformanceMatch
import com.gpiano.app.midi.PerformanceMatcher
import com.gpiano.app.midi.PerformanceReport
import com.gpiano.app.midi.PracticeAttemptRepository
import com.gpiano.app.midi.StoredPracticeAttempt
import com.gpiano.app.scoreworkspace.PlaybackPlan
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.launch

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun MidiPracticeSheet(
    structureId: String,
    sourceRevisionId: String,
    practiceVersionId: String?,
    plan: PlaybackPlan,
    onListen: () -> Unit,
    onBeforeRecord: () -> Unit,
    onOpenCorrection: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current.applicationContext
    val repository = remember { PracticeAttemptRepository(context) }
    val controller = remember { MidiPracticeController(context) }
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var capture by remember { mutableStateOf(controller.currentState()) }
    var startedAt by remember { mutableStateOf<Long?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var result by remember { mutableStateOf<StoredPracticeAttempt?>(null) }
    var recent by remember { mutableStateOf<List<PracticeAttempt>>(emptyList()) }

    controller.onStateChanged = { capture = it }
    DisposableEffect(controller) {
        onDispose { controller.close() }
    }
    LaunchedEffect(structureId) {
        recent = runCatching { repository.recent(structureId) }.getOrDefault(emptyList())
    }

    fun startRecording() {
        onBeforeRecord()
        error = null
        result = null
        runCatching {
            controller.startRecording()
            startedAt = System.currentTimeMillis()
        }.onFailure { error = it.message ?: "无法开始跟弹" }
    }

    fun finishRecording() {
        val captureStartedAt = startedAt ?: return
        val inputSnapshot = capture
        runCatching { controller.stopRecording() }
            .onSuccess { notes ->
                busy = true
                scope.launch {
                    runCatching {
                        val report = PerformanceMatcher.match(plan, notes)
                        repository.save(
                            structureId = structureId,
                            sourceRevisionId = sourceRevisionId,
                            practiceVersionId = practiceVersionId,
                            plan = plan,
                            inputKind = if (inputSnapshot.screenTest) {
                                PracticeAttemptInputKind.ScreenTest
                            } else {
                                PracticeAttemptInputKind.Midi
                            },
                            deviceId = inputSnapshot.selectedDeviceId,
                            deviceName = inputSnapshot.selectedDeviceName ?: "未知 MIDI 设备",
                            startedAt = captureStartedAt,
                            finishedAt = System.currentTimeMillis(),
                            performed = notes,
                            report = report,
                        )
                    }.onSuccess { saved ->
                        result = saved
                        recent = repository.recent(structureId)
                        startedAt = null
                    }.onFailure { error = it.message ?: "无法保存跟弹结果" }
                    busy = false
                }
            }
            .onFailure { error = it.message ?: "无法结束跟弹" }
    }

    ModalBottomSheet(
        onDismissRequest = {
            if (capture.connection == MidiConnectionState.Recording || capture.captureInterrupted) {
                error = "请先完成或取消本次跟弹"
            } else {
                onDismiss()
            }
        },
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.92f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("选段跟弹", style = MaterialTheme.typography.titleLarge)
                    Text(
                        rangeDescription(plan),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(
                    onClick = onDismiss,
                    enabled = capture.connection != MidiConnectionState.Recording && !capture.captureInterrupted,
                ) { Text("完成") }
            }

            Text(
                "只比较当前结构化选段的音高与起音节奏；不评判力度、踏板、音色或艺术处理。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 8.dp),
            )
            OutlinedButton(
                onClick = onListen,
                enabled = capture.connection != MidiConnectionState.Recording && !capture.captureInterrupted,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("先听目标片段") }

            error?.let { message ->
                Card(modifier = Modifier.fillMaxWidth().padding(top = 10.dp)) {
                    Text(message, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(12.dp))
                }
            }

            if (result != null) {
                PerformanceResultContent(
                    stored = requireNotNull(result),
                    onTryAgain = { result = null },
                    onOpenCorrection = onOpenCorrection,
                )
            } else {
                CaptureContent(
                    capture = capture,
                    plan = plan,
                    busy = busy,
                    onRefresh = controller::refreshDevices,
                    onConnect = controller::connect,
                    onUseScreenTest = controller::useScreenTestInput,
                    onStart = ::startRecording,
                    onScreenNote = controller::injectScreenNote,
                    onFinish = ::finishRecording,
                    onCancel = {
                        controller.cancelRecording()
                        startedAt = null
                        error = null
                    },
                )
            }

            if (recent.isNotEmpty()) {
                HorizontalDivider(modifier = Modifier.padding(top = 18.dp, bottom = 12.dp))
                Text("最近跟弹", style = MaterialTheme.typography.titleMedium)
                recent.take(5).forEach { attempt ->
                    TextButton(
                        onClick = {
                            busy = true
                            scope.launch {
                                runCatching { repository.load(attempt.id) }
                                    .onSuccess { result = it }
                                    .onFailure { error = it.message ?: "无法读取跟弹记录" }
                                busy = false
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Text(
                                "第 ${attempt.fromMeasure}–${attempt.toMeasure} 小节 · " +
                                    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                                        .format(Date(attempt.finishedAt)),
                            )
                            Text(
                                "音高 ${attempt.pitchAccuracyPercent}% · 节奏 ${attempt.rhythmAccuracyPercent}% · " +
                                    "漏 ${attempt.missedCount} / 多 ${attempt.extraCount}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(28.dp))
        }
    }
}

@Composable
private fun CaptureContent(
    capture: MidiCaptureUiState,
    plan: PlaybackPlan,
    busy: Boolean,
    onRefresh: () -> Unit,
    onConnect: (com.gpiano.app.midi.MidiInputDevice) -> Unit,
    onUseScreenTest: () -> Unit,
    onStart: () -> Unit,
    onScreenNote: (Int) -> Unit,
    onFinish: () -> Unit,
    onCancel: () -> Unit,
) {
    if (busy) {
        Row(modifier = Modifier.fillMaxWidth().padding(20.dp), horizontalArrangement = Arrangement.Center) {
            CircularProgressIndicator()
        }
        return
    }
    Text("输入设备", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 14.dp))
    when {
        capture.connection == MidiConnectionState.Connecting -> Text("正在连接 MIDI 设备…")
        capture.selectedDeviceName != null -> Text(
            "${if (capture.screenTest) "测试输入" else "已连接"}：${capture.selectedDeviceName}",
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = 4.dp),
        )
        else -> Text(
            "未连接 MIDI 键盘。请先在系统中连接 USB、蓝牙 MIDI 或软件 MIDI 设备。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
    capture.devices.forEach { device ->
        OutlinedButton(
            onClick = { onConnect(device) },
            enabled = capture.connection != MidiConnectionState.Recording,
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
        ) { Text("连接 ${device.name}") }
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        TextButton(onClick = onRefresh) { Text("刷新设备") }
        TextButton(
            onClick = onUseScreenTest,
            enabled = capture.connection != MidiConnectionState.Recording,
        ) { Text("使用屏幕测试输入") }
    }
    if (capture.screenTest) {
        Text(
            "屏幕测试输入只用于验证流程，不代表真实电钢琴连接或演奏表现。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }

    when {
        capture.connection == MidiConnectionState.Recording || capture.captureInterrupted -> {
            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
            Text(
                if (capture.captureInterrupted) "设备中断，已保留 ${capture.capturedNoteCount} 个按键"
                else "正在跟弹 · 已收到 ${capture.capturedNoteCount} 个按键",
                style = MaterialTheme.typography.titleMedium,
            )
            if (capture.screenTest && !capture.captureInterrupted) {
                val targetAttacks = PerformanceMatcher.expectedAttacks(plan)
                val targetPitches = targetAttacks.map { it.midiPitch }.distinct()
                val visibleAttacks = targetAttacks.take(24)
                Text(
                    "目标起音：${visibleAttacks.joinToString("  ") { midiPitchLabel(it.midiPitch) }}" +
                        if (targetAttacks.size > visibleAttacks.size) "  …另 ${targetAttacks.size - visibleAttacks.size} 个" else "",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 8.dp),
                )
                Text(
                    "把下列按键当作测试键盘；点击顺序和间隔都会参与匹配：",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    targetPitches.forEach { pitch ->
                        OutlinedButton(onClick = { onScreenNote(pitch) }) { Text(midiPitchLabel(pitch)) }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                Button(onClick = onFinish) { Text("完成并查看反馈") }
                TextButton(onClick = onCancel) { Text("取消本次") }
            }
        }
        capture.connection == MidiConnectionState.Connected -> {
            if (plan.events.isEmpty()) {
                Text(
                    "当前手别在这个选段中没有可匹配的音符，请返回调整手别或先校正谱面。",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 10.dp),
                )
            }
            Button(
                onClick = onStart,
                enabled = plan.events.isNotEmpty(),
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            ) {
                Text("开始跟弹")
            }
        }
    }
}

@Composable
private fun PerformanceResultContent(
    stored: StoredPracticeAttempt,
    onTryAgain: () -> Unit,
    onOpenCorrection: () -> Unit,
) {
    val report = stored.report
    HorizontalDivider(modifier = Modifier.padding(vertical = 14.dp))
    Text("本次反馈", style = MaterialTheme.typography.titleMedium)
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        MetricCard("音高", report.pitchAccuracyPercent, Modifier.weight(1f))
        MetricCard("节奏", report.rhythmAccuracyPercent, Modifier.weight(1f))
        MetricCard("连续", report.continuityPercent, Modifier.weight(1f))
    }
    Text(
        "目标 ${report.expectedCount} · 实弹 ${report.playedCount} · 漏音 ${report.missedCount} · " +
            "错音 ${report.wrongPitchCount} · 多音 ${report.extraCount}",
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.padding(top = 8.dp),
    )
    Text(
        primaryPracticeAdvice(report),
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(top = 10.dp),
    )
    val issues = report.matches.filter { it.kind != MatchKind.Correct }.take(10)
    if (issues.isNotEmpty()) {
        Text("需要回看的位置", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 12.dp))
        issues.forEach { issue ->
            Text("• ${matchDescription(issue)}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
        }
    } else {
        Text("这一遍的目标音高与起音都落在当前容差内。", modifier = Modifier.padding(top = 12.dp))
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Button(onClick = onTryAgain) { Text("再练一遍") }
        OutlinedButton(onClick = onOpenCorrection) { Text("返回校正谱面") }
    }
    if (stored.attempt.inputKind == PracticeAttemptInputKind.ScreenTest) {
        Text(
            "这条记录来自屏幕测试输入，不能作为真实 MIDI 演奏验证。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

@Composable
private fun MetricCard(label: String, percent: Int, modifier: Modifier = Modifier) {
    Card(modifier = modifier) {
        Column(modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("$percent%", style = MaterialTheme.typography.titleLarge)
            Text(label, style = MaterialTheme.typography.bodySmall)
        }
    }
}

private fun rangeDescription(plan: PlaybackPlan): String {
    val selection = plan.selection
    val range = if (selection.startMeasure == selection.endMeasure) {
        "第 ${selection.startMeasure} 小节"
    } else {
        "第 ${selection.startMeasure}–${selection.endMeasure} 小节"
    }
    return "$range · ${playbackHandLabelForMidi(selection.hand)} · ${(selection.speed * 100).toInt()}% · " +
        "${PerformanceMatcher.expectedAttacks(plan).size} 个目标起音"
}

private fun primaryPracticeAdvice(report: PerformanceReport): String = when {
    report.missedCount > 0 || report.wrongPitchCount > 0 ->
        "先不要提速。按下面标出的小节分手慢练，确认音高后再恢复双手。"
    report.extraCount > 0 ->
        "先缩短到一个小节，检查和弦是否多碰键，再以同一速度重试。"
    report.rhythmAccuracyPercent < report.pitchAccuracyPercent ->
        "音高已较稳定；保持当前速度，针对偏早或偏晚的位置循环两到三遍。"
    report.continuityPercent < 80 ->
        "单个音多数正确，但连续性会中断。把片段再缩短，先练成不中断的一遍。"
    else -> "当前速度下已经稳定，可以保持同一选段再确认一次，或小幅提高速度。"
}

private fun matchDescription(match: PerformanceMatch): String {
    val measure = match.measureIndex?.let { "第 $it 小节" } ?: "片段内"
    return when (match.kind) {
        MatchKind.RhythmEarly -> "$measure：${midiPitchLabel(requireNotNull(match.expectedPitch))} 提前 ${-requireNotNull(match.timingErrorMillis)} ms"
        MatchKind.RhythmLate -> "$measure：${midiPitchLabel(requireNotNull(match.expectedPitch))} 滞后 ${requireNotNull(match.timingErrorMillis)} ms"
        MatchKind.WrongPitch -> "$measure：应弹 ${midiPitchLabel(requireNotNull(match.expectedPitch))}，实际为 ${midiPitchLabel(requireNotNull(match.actualPitch))}"
        MatchKind.Missing -> "$measure：漏弹 ${midiPitchLabel(requireNotNull(match.expectedPitch))}"
        MatchKind.Extra -> "$measure：多弹 ${midiPitchLabel(requireNotNull(match.actualPitch))}"
        MatchKind.Correct -> "$measure：音高和起音正确"
    }
}

private fun midiPitchLabel(pitch: Int): String {
    val names = arrayOf("C", "C♯", "D", "D♯", "E", "F", "F♯", "G", "G♯", "A", "A♯", "B")
    return "${names[pitch % 12]}${pitch / 12 - 1}"
}

private fun playbackHandLabelForMidi(hand: com.gpiano.app.scoreworkspace.PlaybackHand): String = when (hand) {
    com.gpiano.app.scoreworkspace.PlaybackHand.Both -> "双手"
    com.gpiano.app.scoreworkspace.PlaybackHand.Right -> "右手"
    com.gpiano.app.scoreworkspace.PlaybackHand.Left -> "左手"
}
