package com.gpiano.app.ui.screens

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import com.gpiano.app.data.Score
import com.gpiano.app.data.ScoreFormat
import com.gpiano.app.data.format
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val BitmapCacheSizeKb = 24 * 1024

private object ScoreBitmapCache : LruCache<String, Bitmap>(BitmapCacheSizeKb) {
    override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount / 1024
}

@Composable
fun ScoreRenderer(
    score: Score,
    page: Int,
    pageRelativePath: String? = null,
    nextPage: Int? = null,
    nextPageRelativePath: String? = null,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier = modifier) {
        val context = LocalContext.current
        val density = LocalDensity.current
        val targetWidth = with(density) { maxWidth.roundToPx() }.coerceAtLeast(1)
        val targetHeight = with(density) { maxHeight.roundToPx() }.coerceAtLeast(1)
        val request = RenderRequest(page, pageRelativePath)
        var displayedBitmap by remember(score.id) { mutableStateOf<Bitmap?>(null) }

        LaunchedEffect(score.id, request, targetWidth, targetHeight) {
            loadBitmap(context.filesDir, score, request, targetWidth, targetHeight)?.let {
                displayedBitmap = it
            }
        }
        LaunchedEffect(score.id, nextPage, nextPageRelativePath, targetWidth, targetHeight) {
            nextPage?.let {
                loadBitmap(
                    context.filesDir,
                    score,
                    RenderRequest(it, nextPageRelativePath),
                    targetWidth,
                    targetHeight,
                )
            }
        }

        displayedBitmap?.let {
            Image(
                bitmap = it.asImageBitmap(),
                contentDescription = score.title,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit,
            )
        }
    }
}

private data class RenderRequest(val page: Int, val relativePath: String?)

private suspend fun loadBitmap(
    filesDirectory: File,
    score: Score,
    request: RenderRequest,
    targetWidth: Int,
    targetHeight: Int,
): Bitmap? = withContext(Dispatchers.IO) {
    val file = File(filesDirectory, request.relativePath ?: score.relativePath)
    if (!file.exists()) return@withContext null
    val cacheKey = "${file.path}:${request.page}:${targetWidth}x$targetHeight"
    ScoreBitmapCache.get(cacheKey) ?: render(file, score, request.page, targetWidth, targetHeight)?.also {
        ScoreBitmapCache.put(cacheKey, it)
    }
}

private fun render(file: File, score: Score, page: Int, targetWidth: Int, targetHeight: Int): Bitmap? = runCatching {
    if (score.format() == ScoreFormat.Image) {
        decodeSampledBitmap(file, targetWidth, targetHeight)
    } else {
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
            PdfRenderer(descriptor).use { renderer ->
                renderer.openPage(page - 1).use { source ->
                    val scale = minOf(targetWidth.toFloat() / source.width, targetHeight.toFloat() / source.height)
                    val width = (source.width * scale).toInt().coerceAtLeast(1)
                    val height = (source.height * scale).toInt().coerceAtLeast(1)
                    Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { target ->
                        source.render(target, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    }
                }
            }
        }
    }
}.getOrNull()

private fun decodeSampledBitmap(file: File, targetWidth: Int, targetHeight: Int): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.path, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sampleSize = 1
    while (bounds.outWidth / (sampleSize * 2) >= targetWidth && bounds.outHeight / (sampleSize * 2) >= targetHeight) {
        sampleSize *= 2
    }
    return BitmapFactory.decodeFile(
        file.path,
        BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        },
    )
}
