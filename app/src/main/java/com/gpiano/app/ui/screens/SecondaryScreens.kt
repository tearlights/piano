package com.gpiano.app.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun FavoritesScreen(contentPadding: PaddingValues, onOpenReader: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(contentPadding).padding(horizontal = 16.dp)) {
        ScreenHeading("收藏")
        Text("常用曲目会显示在这里。", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(18.dp))
        ScoreCard("献给爱丽丝", "贝多芬 · 第 2 / 4 页", onOpenReader)
    }
}

@Composable
fun FoldersScreen(contentPadding: PaddingValues) {
    Column(Modifier.fillMaxSize().padding(contentPadding).padding(horizontal = 16.dp)) {
        ScreenHeading("文件夹")
        FolderRow("基础练习", "12 份琴谱")
        FolderRow("正在练习", "3 份琴谱")
        FolderRow("演出曲目", "0 份琴谱")
    }
}

@Composable
fun SettingsScreen(contentPadding: PaddingValues) {
    Column(Modifier.fillMaxSize().padding(contentPadding).padding(horizontal = 16.dp)) {
        ScreenHeading("设置")
        SettingRow("阅读偏好", "默认阅读模式与翻页方式")
        SettingRow("数据与备份", "导入或导出 .gpiano")
        SettingRow("关于 Gpiano", "本地优先的钢琴谱阅读器")
    }
}

@Composable
private fun ScreenHeading(title: String) {
    Text(title, modifier = Modifier.padding(top = 24.dp, bottom = 20.dp), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
}

@Composable
private fun FolderRow(name: String, count: String) {
    Text(name, modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp), style = MaterialTheme.typography.titleMedium)
    Text(count, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.height(12.dp))
}

@Composable
private fun SettingRow(name: String, description: String) {
    Text(name, modifier = Modifier.fillMaxWidth().padding(top = 16.dp), style = MaterialTheme.typography.titleMedium)
    Text(description, modifier = Modifier.padding(top = 4.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.height(16.dp))
}
