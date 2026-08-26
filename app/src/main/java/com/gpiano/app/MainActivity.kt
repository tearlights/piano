package com.gpiano.app

import android.os.Bundle
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.view.WindowCompat
import com.gpiano.app.ui.navigation.GpianoApp
import com.gpiano.app.ui.screens.AlphaTabScoreTapBridge
import com.gpiano.app.ui.theme.GpianoTheme

class MainActivity : ComponentActivity() {
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        AlphaTabScoreTapBridge.observe(event)
        return super.dispatchTouchEvent(event)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContent {
            GpianoTheme {
                GpianoApp()
            }
        }
    }
}
