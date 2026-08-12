package com.gpiano.app.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.gpiano.app.viewmodel.BackupUiState
import com.gpiano.app.viewmodel.OmrSettingsUiState
import com.gpiano.app.viewmodel.AiSettingsUiState

@Composable
fun RestoreSettingsScreen(
    contentPadding: PaddingValues,
    backupState: BackupUiState,
    onExport: (Uri) -> Unit,
    onRestore: (Uri) -> Unit,
    omrState: OmrSettingsUiState,
    onSaveOmr: (String, String) -> Unit,
    aiState: AiSettingsUiState,
    onSaveAi: (String, String) -> Unit,
) {
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/vnd.gpiano.backup"),
    ) { uri -> uri?.let(onExport) }
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let(onRestore) }

    var endpoint by remember(omrState.endpoint) { mutableStateOf(omrState.endpoint) }
    var token by remember { mutableStateOf("") }
    var aiEndpoint by remember(aiState.endpoint) { mutableStateOf(aiState.endpoint) }
    var aiToken by remember { mutableStateOf("") }

    Column(
        Modifier.fillMaxSize().padding(contentPadding).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
    ) {
        Text(
            "设置",
            modifier = Modifier.padding(top = 24.dp, bottom = 20.dp),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Text("数据与备份", style = MaterialTheme.typography.titleMedium)
        Text(
            "备份包含原始琴谱、组织信息、书签、页面顺序、结构化修订、练习版本和跟弹记录。",
            modifier = Modifier.padding(top = 6.dp, bottom = 12.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(
            onClick = { exportLauncher.launch("gpiano-backup.gpiano") },
            enabled = !backupState.inProgress,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("导出整个练习资料库") }
        Button(
            onClick = {
                importLauncher.launch(
                    arrayOf("application/vnd.gpiano.backup", "application/zip", "application/octet-stream"),
                )
            },
            enabled = !backupState.inProgress,
            modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
        ) { Text("导入 .gpiano 备份") }
        backupState.message?.let { message ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (backupState.inProgress) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                }
                Text(
                    message,
                    color = if (backupState.isError) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }
        Text(
            "谱面转换服务",
            modifier = Modifier.padding(top = 28.dp),
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            "连接你自己运行的 Audiveris companion。只有点击转换时，所选单页图片才会发送到该地址。",
            modifier = Modifier.padding(top = 6.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = endpoint,
            onValueChange = { endpoint = it },
            label = { Text("服务地址") },
            placeholder = { Text("https://omr.example.com") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
        )
        OutlinedTextField(
            value = token,
            onValueChange = { token = it },
            label = { Text(if (omrState.configured) "访问令牌（留空保持不变）" else "访问令牌") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
        )
        Button(
            onClick = { onSaveOmr(endpoint, token); token = "" },
            enabled = !omrState.inProgress && endpoint.isNotBlank() && (omrState.configured || token.isNotBlank()),
            modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
        ) {
            if (omrState.inProgress) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            }
            Text(if (omrState.configured) "保存并重新检查" else "连接并检查")
        }
        omrState.message?.let { message ->
            Text(
                message,
                modifier = Modifier.padding(top = 10.dp, bottom = 24.dp),
                color = if (omrState.isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            "AI 练习服务",
            modifier = Modifier.padding(top = 28.dp),
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            "连接你自己运行的 practice AI companion。只有在练习工作区逐次确认后，当前选段的结构摘要和问题才会发送；原图与完整 MusicXML 不会发送。",
            modifier = Modifier.padding(top = 6.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = aiEndpoint,
            onValueChange = { aiEndpoint = it },
            label = { Text("服务地址") },
            placeholder = { Text("https://ai.example.com") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
        )
        OutlinedTextField(
            value = aiToken,
            onValueChange = { aiToken = it },
            label = { Text(if (aiState.configured) "访问令牌（留空保持不变）" else "访问令牌") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
        )
        Button(
            onClick = { onSaveAi(aiEndpoint, aiToken); aiToken = "" },
            enabled = !aiState.inProgress && aiEndpoint.isNotBlank() && (aiState.configured || aiToken.isNotBlank()),
            modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
        ) {
            if (aiState.inProgress) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            Text(if (aiState.configured) "保存并重新检查" else "连接并检查")
        }
        aiState.message?.let { message ->
            Text(
                message,
                modifier = Modifier.padding(top = 10.dp, bottom = 24.dp),
                color = if (aiState.isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
