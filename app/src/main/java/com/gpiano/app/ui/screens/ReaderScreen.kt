package com.gpiano.app.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.gpiano.app.ui.theme.ReaderBackdrop
import com.gpiano.app.data.Score
import com.gpiano.app.data.ScoreRepository
import kotlinx.coroutines.flow.flowOf

@Composable
fun ReaderScreen(score: Score?, onBack: () -> Unit, onToggleFavorite: () -> Unit, isFavorite: Boolean, onMoveToFolder: (String?) -> Unit, onDelete: () -> Unit) {
    var controlsVisible by rememberSaveable { mutableStateOf(true) }
    var metronomeVisible by rememberSaveable { mutableStateOf(false) }
    var pageOrderVisible by rememberSaveable { mutableStateOf(false) }
    var folderVisible by rememberSaveable { mutableStateOf(false) }
    var deleteConfirmationVisible by rememberSaveable { mutableStateOf(false) }
    var page by rememberSaveable(score?.id) { mutableIntStateOf(1) }
    val context = LocalContext.current.applicationContext
    val repository = remember(score?.id) { ScoreRepository(context) }
    val pagesFlow = remember(score?.id, repository) {
        score?.let { repository.observePages(it.id) } ?: flowOf(emptyList())
    }
    val pages by pagesFlow.collectAsState(emptyList())
    val pageCount = pages.size.coerceAtLeast(1)
    LaunchedEffect(pageCount) { page = page.coerceIn(1, pageCount) }
    val currentPage = pages.getOrNull(page - 1)
    val sourcePage = currentPage?.sourceIndex?.plus(1) ?: page

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(ReaderBackdrop)
            .pointerInput(page) {
                detectTapGestures { tapOffset ->
                    when {
                        tapOffset.x <= size.width / 3f -> page = (page - 1).coerceAtLeast(1)
                        tapOffset.x >= size.width * 2f / 3f -> page = (page + 1).coerceAtMost(pageCount)
                        else -> {
                            metronomeVisible = false
                            pageOrderVisible = false
                            folderVisible = false
                            deleteConfirmationVisible = false
                            controlsVisible = !controlsVisible
                        }
                    }
                }
            },
    ) {
        if (score != null) {
            ScoreRenderer(
                score = score,
                page = sourcePage,
                pageRelativePath = currentPage?.relativePath,
                nextPage = pages.getOrNull(page)?.let { it.sourceIndex + 1 },
                nextPageRelativePath = pages.getOrNull(page)?.relativePath,
                modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 48.dp),
            )
        } else {
            PlaceholderScore(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 48.dp))
        }
        Text("$page / $pageCount", modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (controlsVisible) {
            ReaderControls(
                onBack = onBack,
                onMetronome = { metronomeVisible = true },
                onPageOrder = { pageOrderVisible = true },
                page = page,
                pageCount = pageCount,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
            ReaderTopBar(title = score?.title ?: "献给爱丽丝", onBack = onBack, onFavorite = onToggleFavorite, isFavorite = isFavorite, onMoveToFolder = { folderVisible = true }, onPageOrder = { pageOrderVisible = true }, onDelete = { deleteConfirmationVisible = true }, modifier = Modifier.align(Alignment.TopCenter))
        }
        if (folderVisible && score != null) {
            androidx.compose.ui.window.Dialog(onDismissRequest = { folderVisible = false }) {
                Surface(shape = RoundedCornerShape(20.dp)) { FolderAssignmentSheet(score, onMoveToFolder) { folderVisible = false } }
            }
        }
        if (pageOrderVisible && score != null) {
            androidx.compose.material3.Surface(modifier = Modifier.align(Alignment.BottomCenter)) { PageOrderSheet(score = score, onClose = { pageOrderVisible = false }) }
        }
        if (deleteConfirmationVisible) {
            AlertDialog(
                onDismissRequest = { deleteConfirmationVisible = false },
                title = { Text("删除琴谱？") },
                text = { Text("将从本机删除这份琴谱及其全部页面，无法恢复。") },
                confirmButton = { FilledTonalButton(onClick = { deleteConfirmationVisible = false; onDelete() }) { Text("删除") } },
                dismissButton = { Text("取消", modifier = Modifier.clickable { deleteConfirmationVisible = false }) },
            )
        }
        if (metronomeVisible) {
            MetronomeSheet(onClose = { metronomeVisible = false }, modifier = Modifier.align(Alignment.BottomCenter))
        }
    }
}

@Composable
private fun ReaderTopBar(title: String, onBack: () -> Unit, onFavorite: () -> Unit, isFavorite: Boolean, onMoveToFolder: () -> Unit, onPageOrder: () -> Unit, onDelete: () -> Unit, modifier: Modifier = Modifier) {
    var moreExpanded by remember { mutableStateOf(false) }
    Surface(modifier = modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f)) {
        Row(modifier = Modifier.fillMaxWidth().padding(top = 28.dp, bottom = 8.dp, start = 4.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Outlined.ArrowBack, "返回") }
            Text(title, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
            IconButton(onClick = onFavorite) { Icon(if (isFavorite) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder, "收藏") }
            Box {
                IconButton(onClick = { moreExpanded = true }) { Icon(Icons.Outlined.MoreVert, "更多") }
                DropdownMenu(expanded = moreExpanded, onDismissRequest = { moreExpanded = false }) {
                    DropdownMenuItem(text = { Text("调整页面顺序") }, onClick = { moreExpanded = false; onPageOrder() })
                    DropdownMenuItem(text = { Text("移动到文件夹") }, onClick = { moreExpanded = false; onMoveToFolder() })
                    DropdownMenuItem(text = { Text("删除琴谱") }, onClick = { moreExpanded = false; onDelete() })
                }
            }
        }
    }
}

@Composable
private fun ReaderControls(
    onBack: () -> Unit,
    onMetronome: () -> Unit,
    onPageOrder: () -> Unit,
    page: Int,
    pageCount: Int,
    modifier: Modifier = Modifier,
) {
    Surface(modifier = modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f)) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("‹", style = MaterialTheme.typography.headlineSmall)
                Text("第 $page 页 / 共 $pageCount 页", style = MaterialTheme.typography.labelLarge)
                Text("›", style = MaterialTheme.typography.headlineSmall)
            }
            Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.SpaceAround) {
                Text("模式", modifier = Modifier.clickable(onClick = onPageOrder), style = MaterialTheme.typography.labelLarge)
                Text("节拍器", modifier = Modifier.clickable(onClick = onMetronome), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

@Composable
private fun MetronomeSheet(onClose: () -> Unit, modifier: Modifier = Modifier) {
    var bpm by remember { mutableIntStateOf(80) }
    var isPlaying by remember { mutableStateOf(false) }
    MetronomeEngine(isPlaying = isPlaying, bpm = bpm, beatsPerBar = 4)
    Surface(modifier = modifier.fillMaxWidth(), shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp), shadowElevation = 8.dp) {
        Column(modifier = Modifier.padding(24.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("节拍器", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Text("完成", modifier = Modifier.clickable(onClick = onClose), color = MaterialTheme.colorScheme.primary)
            }
            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 22.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                Text("−", modifier = Modifier.clickable { bpm = (bpm - 1).coerceAtLeast(30) }, style = MaterialTheme.typography.headlineMedium)
                Text("$bpm BPM", modifier = Modifier.padding(horizontal = 28.dp), style = MaterialTheme.typography.headlineMedium)
                Text("+", modifier = Modifier.clickable { bpm = (bpm + 1).coerceAtMost(300) }, style = MaterialTheme.typography.headlineMedium)
            }
            Text("4 / 4", style = MaterialTheme.typography.titleMedium)
            FilledTonalButton(onClick = { isPlaying = !isPlaying }, modifier = Modifier.fillMaxWidth().padding(top = 20.dp)) { Text(if (isPlaying) "停止" else "开始") }
        }
    }
}

@Composable
private fun PlaceholderScore(modifier: Modifier = Modifier) {
    Surface(modifier = modifier.clip(RoundedCornerShape(4.dp)), color = Color.White, shadowElevation = 1.dp) {
        Canvas(modifier = Modifier.fillMaxSize().padding(22.dp)) {
            val gap = size.height / 12f
            repeat(10) { index ->
                val y = gap * (index + 1)
                drawLine(Color(0xFF4A4A46), start = androidx.compose.ui.geometry.Offset(0f, y), end = androidx.compose.ui.geometry.Offset(size.width, y), strokeWidth = 1.2f)
            }
            drawCircle(Color(0xFF2F302E), center = androidx.compose.ui.geometry.Offset(size.width * 0.27f, gap * 3f), radius = 9f, style = Stroke(width = 4f))
            drawCircle(Color(0xFF2F302E), center = androidx.compose.ui.geometry.Offset(size.width * 0.62f, gap * 7f), radius = 9f, style = Stroke(width = 4f))
        }
    }
}
