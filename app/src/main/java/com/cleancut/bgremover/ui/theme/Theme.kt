package com.cleancut.bgremover.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val DarkColorScheme = darkColorScheme(
    primary = AccentBlueLight,
    onPrimary = Color.White,
    primaryContainer = AccentBlueDark,
    onPrimaryContainer = Color.White,
    background = Slate950,
    onBackground = Slate50,
    surface = Slate900,
    onSurface = Slate50,
    surfaceVariant = Slate850,
    onSurfaceVariant = Slate300,
    surfaceContainer = Slate850,
    surfaceContainerHigh = Slate800,
    outline = Slate700,
    outlineVariant = Slate800
)

private val LightColorScheme = lightColorScheme(
    primary = AccentBlue,
    onPrimary = Color.White,
    primaryContainer = AccentBlueSubtle,
    onPrimaryContainer = AccentBlueDark,
    background = Slate50,
    onBackground = Slate900,
    surface = PresetWhite,
    onSurface = Slate900,
    surfaceVariant = Slate100,
    onSurfaceVariant = Slate600,
    surfaceContainer = Slate100,
    surfaceContainerHigh = Slate200,
    outline = Slate200,
    outlineVariant = Slate100
)

@Composable
fun CleanCutTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
    val view = LocalView.current

    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = android.graphics.Color.TRANSPARENT
            window.navigationBarColor = android.graphics.Color.TRANSPARENT
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
            WindowCompat.getInsetsController(window, view).isAppearanceLightNavigationBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
