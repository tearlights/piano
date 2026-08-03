package com.gpiano.app.ui.screens

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
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.gpiano.app.data.GpianoBackupManager
import com.gpiano.app.data.Score

@Composable
fun BackupSettingsScreen(contentPadding: PaddingValues, scores: List<Score>) {
    val context = LocalContext.current
    val backupManager = remember { GpianoBackupManager(context.applicationContext) }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri -> uri?.let { backupManager.export(scores, it) } }
    Column(Modifier.fillMaxSize().padding(contentPadding).padding(horizontal = 16.dp)) {
        Text("设置", modifier = Modifier.padding(top = 24.dp, bottom = 20.dp), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
        Text("数据与备份", style = MaterialTheme.typography.titleMedium)
        Text("备份包含琴谱原文件和本地阅读数据。", modifier = Modifier.padding(top = 6.dp, bottom = 12.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Button(onClick = { exportLauncher.launch("gpiano-backup.gpiano") }, modifier = Modifier.fillMaxWidth()) { Text("导出整个琴谱库") }
    }
}
