package com.gpiano.app.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.PickVisualMediaRequest
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.IconButton
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.gpiano.app.data.Score
import com.gpiano.app.data.ScoreFormat
import com.gpiano.app.data.format
import com.gpiano.app.data.RecognitionJob
import com.gpiano.app.data.RecognitionJobStatus

@Composable
fun ImportedLibraryScreen(
    contentPadding: PaddingValues,
    scores: List<Score>,
    importError: String?,
    importInProgress: Boolean,
    onImport: (Uri) -> Unit,
    onImportAll: (List<Uri>) -> Unit,
    onRename: (Score, String) -> Unit,
    onOpenReader: (Score) -> Unit,
    recognitionJobs: List<RecognitionJob>,
    onRecognize: (Score) -> Unit,
    onRetryRecognition: (String) -> Unit,
    onCancelRecognition: (String) -> Unit,
    onOpenWorkspace: (String) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var importChoiceVisible by remember { mutableStateOf(false) }
    var scoreToRename by remember { mutableStateOf<Score?>(null) }
    var newTitle by remember { mutableStateOf("") }
    val filteredScores = scores.filter { it.title.contains(query, ignoreCase = true) }
    val latestJobs = recognitionJobs.groupBy(RecognitionJob::sourceScoreId).mapValues { (_, jobs) -> jobs.maxBy(RecognitionJob::createdAt) }
    val pdfPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(onImport) }
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(30)) { uris -> onImportAll(uris) }
    Column(Modifier.fillMaxSize().padding(contentPadding).padding(horizontal = 16.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Gpiano", modifier = Modifier.weight(1f), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
            Button(onClick = { importChoiceVisible = true }, enabled = !importInProgress) {
                if (importInProgress) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Outlined.Add, null)
                }
                Spacer(Modifier.width(4.dp))
                Text(if (importInProgress) "正在导入…" else "导入")
            }
        }
        if (importChoiceVisible) {
            AlertDialog(
                onDismissRequest = { importChoiceVisible = false },
                title = { Text("导入琴谱") },
                text = { Text("请选择要导入的格式。选择图片后可勾选多张，并会合并成一份琴谱。") },
                confirmButton = { Button(onClick = { importChoiceVisible = false; pdfPicker.launch(arrayOf("application/pdf")) }) { Text("PDF") } },
                dismissButton = { Button(onClick = { importChoiceVisible = false; imagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) { Text("图片") } },
            )
        }
        OutlinedTextField(value = query, onValueChange = { query = it }, modifier = Modifier.fillMaxWidth().padding(top = 16.dp), label = { Text("搜索曲名") }, singleLine = true)
        importError?.let { Text(it, modifier = Modifier.padding(top = 12.dp), color = MaterialTheme.colorScheme.error) }
        if (importInProgress) {
            Text("正在复制并校验曲谱，请勿重复选择文件。", modifier = Modifier.padding(top = 12.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (filteredScores.isEmpty()) {
            Spacer(Modifier.height(56.dp))
            Text("还没有琴谱", style = MaterialTheme.typography.titleMedium)
            Text("点击右上角“导入”添加 PDF 或图片琴谱。", modifier = Modifier.padding(top = 6.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            Text("全部琴谱 · ${filteredScores.size} 份", modifier = Modifier.padding(top = 28.dp, bottom = 10.dp), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            filteredScores.forEach { score ->
                ImportedScoreCard(
                    score,
                    recognitionJob = latestJobs[score.id],
                    onClick = { onOpenReader(score) },
                    onRecognize = { onRecognize(score) },
                    onRetryRecognition = onRetryRecognition,
                    onCancelRecognition = onCancelRecognition,
                    onOpenWorkspace = onOpenWorkspace,
                    onRename = {
                        scoreToRename = score
                        newTitle = score.title
                    },
                )
                Spacer(Modifier.height(8.dp))
            }
        }
    }
    scoreToRename?.let { score ->
        AlertDialog(
            onDismissRequest = { scoreToRename = null },
            title = { Text("修改琴谱名称") },
            text = {
                OutlinedTextField(
                    value = newTitle,
                    onValueChange = { newTitle = it },
                    label = { Text("琴谱名称") },
                    singleLine = true,
                )
            },
            confirmButton = {
                Button(onClick = {
                    onRename(score, newTitle)
                    scoreToRename = null
                }, enabled = newTitle.isNotBlank()) { Text("保存") }
            },
            dismissButton = { Button(onClick = { scoreToRename = null }) { Text("取消") } },
        )
    }
}

@Composable
private fun ImportedScoreCard(
    score: Score,
    recognitionJob: RecognitionJob?,
    onClick: () -> Unit,
    onRecognize: () -> Unit,
    onRetryRecognition: (String) -> Unit,
    onCancelRecognition: (String) -> Unit,
    onOpenWorkspace: (String) -> Unit,
    onRename: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick), shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Card(modifier = Modifier.size(62.dp), shape = RoundedCornerShape(10.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {}
            Column(Modifier.padding(start = 14.dp).weight(1f)) {
                Text(score.title, style = MaterialTheme.typography.titleMedium)
                Text(
                    recognitionStatusText(score, recognitionJob),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (recognitionJob?.status == RecognitionJobStatus.Failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                )
                when {
                    recognitionJob?.status == RecognitionJobStatus.Pending || recognitionJob?.status == RecognitionJobStatus.Running -> {
                        TextButton(onClick = { onCancelRecognition(recognitionJob.id) }) { Text("取消转换") }
                    }
                    recognitionJob?.status == RecognitionJobStatus.Ready && recognitionJob.resultStructureId != null -> {
                        TextButton(onClick = { onOpenWorkspace(recognitionJob.resultStructureId) }) { Text("打开练习谱") }
                    }
                    recognitionJob?.status == RecognitionJobStatus.Failed || recognitionJob?.status == RecognitionJobStatus.Cancelled -> {
                        TextButton(onClick = { onRetryRecognition(recognitionJob.id) }) { Text("重新转换") }
                    }
                    recognitionJob == null && score.format() == ScoreFormat.Image -> {
                        TextButton(onClick = onRecognize) { Text("转换为练习谱") }
                    }
                }
            }
            IconButton(onClick = onRename) { Icon(Icons.Outlined.Edit, contentDescription = "修改琴谱名称") }
        }
    }
}

private fun recognitionStatusText(score: Score, job: RecognitionJob?): String = when (job?.status) {
    RecognitionJobStatus.Pending -> "等待 OMR 服务 · 可退出后自动继续"
    RecognitionJobStatus.Running -> when (job.stage) {
        "uploading" -> "正在安全发送这一页…"
        "audiveris", "recognizing" -> "Audiveris 正在识别…"
        else -> "谱面转换进行中…"
    }
    RecognitionJobStatus.Ready -> "结构化草稿待校正"
    RecognitionJobStatus.Failed -> job.errorMessage ?: "转换失败"
    RecognitionJobStatus.Cancelled -> "转换已取消"
    else -> if (score.format() == ScoreFormat.Pdf) "PDF · 当前 OMR 先支持单页图片" else "图片 · 本地保存"
}
