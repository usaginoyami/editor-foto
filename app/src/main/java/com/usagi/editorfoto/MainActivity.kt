package com.usagi.editorfoto

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color

class MainActivity : ComponentActivity() {
    private val vm: EditorViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AppTheme {
                val opened by vm.opened.collectAsState()
                val loading by vm.loading.collectAsState()
                if (!opened && !loading) HomeScreen(vm) else EditorScreen(vm)
            }
        }
    }
}

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Color(0xFFE63946),
            onPrimary = Color.White,
            background = Color(0xFF101014),
            surface = Color(0xFF1A1A20),
            onSurface = Color(0xFFEAEAEA),
            surfaceVariant = Color(0xFF26262E),
            onSurfaceVariant = Color(0xFFB8B8C0)
        ),
        content = content
    )
}
