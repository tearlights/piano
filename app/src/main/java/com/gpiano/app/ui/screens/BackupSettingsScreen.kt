package com.gpiano.app.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun BackupSettingsScreen(contentPadding: PaddingValues, onExport: (Uri) -> Unit) {
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/vnd.gpiano.backup"),
    ) { uri -> uri?.let(onExport) }
    Column(Modifier.fillMaxSize().padding(contentPadding).padding(horizontal = 16.dp)) {
        Text(
            "设置",
            modifier = Modifier.padding(top = 24.dp, bottom = 20.dp),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Text("数据与备份", style = MaterialTheme.typography.titleMedium)
        Text(
            "备份包含琴谱原文件、本地阅读数据、结构化修订、练习版本和跟弹记录。",
            modifier = Modifier.padding(top = 6.dp, bottom = 12.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(
            onClick = { exportLauncher.launch("gpiano-backup.gpiano") },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("导出整个练习资料库") }
    }
}
