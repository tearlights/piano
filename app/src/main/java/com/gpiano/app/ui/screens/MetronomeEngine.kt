package com.gpiano.app.ui.screens

import android.media.AudioManager
import android.media.ToneGenerator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.delay

@Composable
fun MetronomeEngine(isPlaying: Boolean, bpm: Int, beatsPerBar: Int) {
    val tone = androidx.compose.runtime.remember { ToneGenerator(AudioManager.STREAM_MUSIC, 75) }
    DisposableEffect(Unit) { onDispose { tone.release() } }
    LaunchedEffect(isPlaying, bpm, beatsPerBar) {
        if (!isPlaying) return@LaunchedEffect
        var beat = 0
        while (true) {
            tone.startTone(if (beat == 0) ToneGenerator.TONE_PROP_BEEP2 else ToneGenerator.TONE_PROP_BEEP, 45)
            beat = (beat + 1) % beatsPerBar
            delay((60_000L / bpm).coerceAtLeast(50L))
        }
    }
}
