package com.example.pushbridge.theme

import android.app.Activity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

val LocalPushBridgePalette = staticCompositionLocalOf { PushBridgePalettes.Midnight }

object PushBridgeThemeTokens {
    val colors: PushBridgePalette
        @Composable
        @ReadOnlyComposable
        get() = LocalPushBridgePalette.current
}

@Composable
fun PushBridgeTheme(
    themeId: String = "midnight",
    content: @Composable () -> Unit
) {
    val palette = PushBridgePalettes.byId(themeId)
    val colorScheme = if (palette.isLight) {
        lightColorScheme(
            primary = palette.primary,
            onPrimary = palette.onPrimary,
            secondary = palette.accent,
            tertiary = GroupPurple,
            background = palette.background,
            onBackground = palette.textMain,
            surface = palette.surface,
            onSurface = palette.textMain,
            surfaceVariant = palette.surfaceSubtle,
            onSurfaceVariant = palette.textMuted,
            outline = palette.border,
            error = RoseAlert
        )
    } else {
        darkColorScheme(
            primary = palette.primary,
            onPrimary = palette.onPrimary,
            secondary = palette.accent,
            tertiary = GroupPurple,
            background = palette.background,
            onBackground = palette.textMain,
            surface = palette.surface,
            onSurface = palette.textMain,
            surfaceVariant = palette.surfaceSubtle,
            onSurfaceVariant = palette.textMuted,
            outline = palette.border,
            error = RoseAlert
        )
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window
            if (window != null) {
                WindowCompat.getInsetsController(window, view).apply {
                    isAppearanceLightStatusBars = palette.isLight
                    isAppearanceLightNavigationBars = palette.isLight
                }
            }
        }
    }

    CompositionLocalProvider(LocalPushBridgePalette provides palette) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography,
            content = content
        )
    }
}
