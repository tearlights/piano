package com.gpiano.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.view.WindowCompat
import com.gpiano.app.ui.navigation.GpianoApp
import com.gpiano.app.ui.theme.GpianoTheme

class MainActivity : ComponentActivity() {
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
