package com.gpiano.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.gpiano.app.data.PracticeVersion
import com.gpiano.app.data.PracticeVersionStatus
import com.gpiano.app.scoreworkspace.PracticeDifference
import com.gpiano.app.scoreworkspace.PracticeDifferenceKind
import com.gpiano.app.scoreworkspace.PracticeVersionDocument
import com.gpiano.app.scoreworkspace.PracticeVersionPreset

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun PracticeVersionsSheet(
    fromMeasure: Int,
    toMeasure: Int,
    versions: List<PracticeVersion>,
    inspected: PracticeVersionDocument?,
    busy: Boolean,
    error: String?,
    onCreate: (PracticeVersionPreset) -> Unit,
    onInspect: (String) -> Unit,
    onPreview: (PracticeVersionDocument) -> Unit,
    onAccept: (String) -> Unit,
    onReject: (String) -> Unit,
    onExport: (PracticeVersion) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val range = if (fromMeasure == toMeasure) "第 $fromMeasure 小节" else "第 $fromMeasure–$toMeasure 小节"
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier.fillMaxWidth().fillMaxHeight(0.88f).padding(horizontal = 16.dp),
        ) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("派生练习版本", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "$range · 原谱和校正主谱不会被覆盖",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (busy) CircularProgressIndicator(modifier = Modifier.padding(12.dp))
                TextButton(onClick = onDismiss, enabled = !busy) { Text("完成") }
            }
            error?.let {
                Text(
                    it,
                    modifier = Modifier.padding(top = 6.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            LazyColumn(
                modifier = Modifier.fillMaxWidth().weight(1f),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item {
                    Text("为当前范围生成", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
                    Text(
                        "每个方案先生成独立 MusicXML 候选和事件差异，审阅后再决定是否采纳。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                items(PracticeVersionPreset.entries, key = PracticeVersionPreset::code) { preset ->
                    Card(
                        modifier = Modifier.fillMaxWidth().clickable(enabled = !busy) { onCreate(preset) },
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(preset.displayName, style = MaterialTheme.typography.titleSmall)
                            Text(
                                preset.purpose,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 3.dp),
                            )
                            Text("生成候选并查看差异", color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 6.dp))
                        }
                    }
                }

                inspected?.let { document ->
                    item { HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp)) }
                    item { PracticeVersionDetails(document, busy, onPreview, onAccept, onReject, onExport) }
                }

                item {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                    Text("已保存版本", style = MaterialTheme.typography.titleSmall)
                }
                if (versions.isEmpty()) {
                    item {
                        Text(
                            "还没有派生版本。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    items(versions, key = PracticeVersion::id) { version ->
                        Surface(
                            modifier = Modifier.fillMaxWidth().clickable(enabled = !busy) { onInspect(version.id) },
                            shape = MaterialTheme.shapes.medium,
                            tonalElevation = if (inspected?.version?.id == version.id) 5.dp else 1.dp,
                        ) {
                            Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(version.title, style = MaterialTheme.typography.titleSmall)
                                    Text(
                                        "${practiceVersionStatusLabel(version.status)} · 基于主谱修订 ${versionBaseLabel(version.baseRevisionId)}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                Text("查看", color = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }
}

@Composable
private fun PracticeVersionDetails(
    document: PracticeVersionDocument,
    busy: Boolean,
    onPreview: (PracticeVersionDocument) -> Unit,
    onAccept: (String) -> Unit,
    onReject: (String) -> Unit,
    onExport: (PracticeVersion) -> Unit,
) {
    val version = document.version
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(version.title, style = MaterialTheme.typography.titleMedium)
        Text(
            "${practiceVersionStatusLabel(version.status)} · ${document.differences.size} 项变化",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(version.purpose, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 6.dp))
        document.differences.take(30).forEach { difference ->
            DifferenceRow(difference)
        }
        if (document.differences.size > 30) {
            Text(
                "另有 ${document.differences.size - 30} 项变化；导出的 MusicXML 保留全部结果。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
        Button(
            onClick = { onPreview(document) },
            enabled = !busy && version.status != PracticeVersionStatus.Rejected,
            modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
        ) { Text("在工作区预览并试听") }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (version.status == PracticeVersionStatus.Draft) {
                OutlinedButton(onClick = { onAccept(version.id) }, enabled = !busy, modifier = Modifier.weight(1f)) {
                    Text("采纳")
                }
                TextButton(onClick = { onReject(version.id) }, enabled = !busy, modifier = Modifier.weight(1f)) {
                    Text("拒绝")
                }
            }
            TextButton(onClick = { onExport(version) }, enabled = !busy, modifier = Modifier.weight(1f)) {
                Text("导出 MusicXML")
            }
        }
    }
}

@Composable
private fun DifferenceRow(difference: PracticeDifference) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(top = 7.dp),
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Text(
                "第 ${difference.measureIndex} 小节 · ${differenceKindLabel(difference.kind)} · ${difference.before} → ${difference.after}",
                style = MaterialTheme.typography.labelLarge,
            )
            Text(
                difference.explanation,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

fun practiceVersionStatusLabel(status: String): String = when (status) {
    PracticeVersionStatus.Draft -> "待审阅"
    PracticeVersionStatus.Accepted -> "已采纳"
    PracticeVersionStatus.Rejected -> "已拒绝"
    else -> "未知状态"
}

private fun differenceKindLabel(kind: PracticeDifferenceKind): String = when (kind) {
    PracticeDifferenceKind.Muted -> "分手静音"
    PracticeDifferenceKind.RemovedChordTone -> "减少和弦音"
    PracticeDifferenceKind.OctaveShift -> "八度等价移动"
}

private fun versionBaseLabel(baseRevisionId: String): String = baseRevisionId.take(8)
