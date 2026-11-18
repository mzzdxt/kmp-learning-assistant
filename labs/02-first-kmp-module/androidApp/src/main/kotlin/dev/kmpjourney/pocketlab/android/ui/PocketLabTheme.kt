package dev.kmpjourney.pocketlab.android.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val PocketLabColors = lightColorScheme(
    primary = Color(0xFF315E55),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD2E8DF),
    onPrimaryContainer = Color(0xFF153D36),
    secondary = Color(0xFF8C5B32),
    secondaryContainer = Color(0xFFFFDCC1),
    background = Color(0xFFF8FAF7),
    surface = Color(0xFFF8FAF7),
    surfaceVariant = Color(0xFFE2E9E5),
)

@Composable
fun PocketLabTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = PocketLabColors,
        content = content,
    )
}
