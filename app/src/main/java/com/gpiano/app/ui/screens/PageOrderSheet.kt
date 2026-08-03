package com.gpiano.app.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
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
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope

@Composable
fun PageOrderSheet(score: Score, onClose: () -> Unit) {
    val context = LocalContext.current.applicationContext
    val repository = remember { ScoreRepository(context) }
    val pages by repository.observePages(score.id).collectAsState(emptyList())
    val scope = rememberCoroutineScope()
    Column(Modifier.padding(24.dp)) {
        Text("调整页面顺序")
        pages.forEachIndexed { index, page ->
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                Text("第 ${page.sourceIndex + 1} 页", modifier = Modifier.weight(1f))
                Button(onClick = { scope.launch { repository.movePage(score.id, page.displayIndex, -1) } }, enabled = index > 0) { Text("上移") }
                Button(onClick = { scope.launch { repository.movePage(score.id, page.displayIndex, 1) } }, enabled = index < pages.lastIndex, modifier = Modifier.padding(start = 8.dp)) { Text("下移") }
            }
        }
        Button(onClick = { scope.launch { repository.restorePageOrder(score.id) } }, modifier = Modifier.fillMaxWidth()) { Text("恢复原始顺序") }
        Button(onClick = onClose, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) { Text("完成") }
    }
}
