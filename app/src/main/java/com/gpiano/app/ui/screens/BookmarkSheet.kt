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
fun BookmarkSheet(score: Score, onJump: (Int) -> Unit, onClose: () -> Unit) {
    val context = LocalContext.current.applicationContext
    val repository = remember { ScoreRepository(context) }
    val bookmarks by repository.observeBookmarks(score.id).collectAsState(emptyList())
    Column(Modifier.padding(24.dp)) {
        Text("书签")
        if (bookmarks.isEmpty()) Text("尚未添加书签", modifier = Modifier.padding(top = 16.dp))
        bookmarks.forEach { bookmark -> Text("第 ${bookmark.page} 页", modifier = Modifier.fillMaxWidth().clickable { onJump(bookmark.page); onClose() }.padding(vertical = 14.dp)) }
    }
}
