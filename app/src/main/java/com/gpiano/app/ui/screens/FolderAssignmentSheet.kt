package com.gpiano.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.gpiano.app.data.Score
import com.gpiano.app.data.ScoreRepository

@Composable
fun FolderAssignmentSheet(score: Score, onMove: (String?) -> Unit, onClose: () -> Unit) {
    val context = LocalContext.current.applicationContext
    val repository = remember { ScoreRepository(context) }
    val folders by repository.observeFolders().collectAsState(emptyList())
    Column(Modifier.padding(24.dp)) {
        Text("移动到文件夹")
        Text("未分类", modifier = Modifier.fillMaxWidth().clickable { onMove(null); onClose() }.padding(vertical = 16.dp))
        folders.forEach { folder -> Text(folder.name, modifier = Modifier.fillMaxWidth().clickable { onMove(folder.id); onClose() }.padding(vertical = 16.dp)) }
    }
}
