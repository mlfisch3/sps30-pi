package com.sensirion.sps30.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Brand seed close to the launcher background (teal), kept simple for phase 1.
private val Teal = Color(0xFF0B7285)
private val TealLight = Color(0xFF3BAFCB)

private val DarkColors = darkColorScheme(
    primary = TealLight,
    secondary = TealLight,
)
private val LightColors = lightColorScheme(
    primary = Teal,
    secondary = Teal,
)

@Composable
fun Sps30Theme(
    useDarkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (useDarkTheme) DarkColors else LightColors,
        content = content,
    )
}
