package com.gpiano.app.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.gpiano.app.data.Score

@Composable
fun RealFavoritesScreen(
    contentPadding: PaddingValues,
    scores: List<Score>,
    onOpenReader: (Score) -> Unit,
) {
    Column(Modifier.fillMaxSize().padding(contentPadding).padding(horizontal = 16.dp)) {
        Text("收藏", modifier = Modifier.padding(top = 24.dp, bottom = 20.dp), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
        if (scores.isEmpty()) {
            Text("还没有收藏曲谱", style = MaterialTheme.typography.titleMedium)
            Text("在阅读器右上角点击星标，即可将曲谱收藏到这里。", modifier = Modifier.padding(top = 6.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            scores.forEach { score ->
                ScoreCard(score.title, if (score.mimeType == "application/pdf") "PDF · 已收藏" else "图片 · 已收藏", onClick = { onOpenReader(score) })
                Spacer(Modifier.padding(4.dp))
            }
        }
    }
}
