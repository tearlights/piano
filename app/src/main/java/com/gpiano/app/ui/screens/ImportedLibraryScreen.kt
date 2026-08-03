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
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.IconButton
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

@Composable
fun ImportedLibraryScreen(
    contentPadding: PaddingValues,
    scores: List<Score>,
    importError: String?,
    onImport: (Uri) -> Unit,
    onImportAll: (List<Uri>) -> Unit,
    onRename: (Score, String) -> Unit,
    onOpenReader: (Score) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var importChoiceVisible by remember { mutableStateOf(false) }
    var scoreToRename by remember { mutableStateOf<Score?>(null) }
    var newTitle by remember { mutableStateOf("") }
    val filteredScores = scores.filter { it.title.contains(query, ignoreCase = true) }
    val pdfPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(onImport) }
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(30)) { uris -> onImportAll(uris) }
    Column(Modifier.fillMaxSize().padding(contentPadding).padding(horizontal = 16.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Gpiano", modifier = Modifier.weight(1f), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
            Button(onClick = { importChoiceVisible = true }) {
                Icon(Icons.Outlined.Add, null)
                Spacer(Modifier.width(4.dp))
                Text("导入")
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
        if (filteredScores.isEmpty()) {
            Spacer(Modifier.height(56.dp))
            Text("还没有琴谱", style = MaterialTheme.typography.titleMedium)
            Text("点击右上角“导入”添加 PDF 或图片琴谱。", modifier = Modifier.padding(top = 6.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            Text("全部琴谱 · ${filteredScores.size} 份", modifier = Modifier.padding(top = 28.dp, bottom = 10.dp), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            filteredScores.forEach { score ->
                ImportedScoreCard(
                    score,
                    onClick = { onOpenReader(score) },
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
private fun ImportedScoreCard(score: Score, onClick: () -> Unit, onRename: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick), shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Card(modifier = Modifier.size(62.dp), shape = RoundedCornerShape(10.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {}
            Column(Modifier.padding(start = 14.dp)) {
                Text(score.title, style = MaterialTheme.typography.titleMedium)
                Text(if (score.format() == ScoreFormat.Pdf) "PDF · 本地保存" else "图片 · 本地保存", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = onRename) { Icon(Icons.Outlined.Edit, contentDescription = "修改琴谱名称") }
        }
    }
}
