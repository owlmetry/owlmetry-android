package com.owlmetry.android.demo

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.platform.LocalContext
import android.os.Build

/**
 * The single launcher Activity. Hosts the [DemoScreen] inside a Material3 theme.
 * The Android analog of the Swift demo's `WindowGroup { ContentView() }`.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val context = LocalContext.current
            // Material You dynamic color on Android 12+, else a static light scheme.
            val colors = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                dynamicLightColorScheme(context)
            } else {
                lightColorScheme()
            }
            MaterialTheme(colorScheme = colors) {
                Surface { DemoScreen() }
            }
        }
    }
}
