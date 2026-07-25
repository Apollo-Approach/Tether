package com.rover.remote.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.Shapes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

private val TetherColorScheme = darkColorScheme(
    // Backgrounds
    background = DarkBackground,
    surface = DarkSurface,
    surfaceVariant = DarkSurfaceVariant,
    surfaceContainerHighest = DarkSurfaceElevated,

    // Primary accent (warm amber)
    primary = Amber,
    onPrimary = Color(0xFF1C1000),
    primaryContainer = UserBubble,
    onPrimaryContainer = UserBubbleText,

    // Secondary accent (purple)
    secondary = AccentPurple,
    onSecondary = Color.White,
    secondaryContainer = AssistantBubble,
    onSecondaryContainer = AssistantBubbleText,

    // Tertiary (blue)
    tertiary = AccentBlue,
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFF1E3A5F),
    onTertiaryContainer = Color(0xFFBFDBFE),

    // Error
    error = Color(0xFFEF4444),
    onError = Color.White,
    errorContainer = SystemBubble,
    onErrorContainer = SystemBubbleText,

    // Text & icons
    onBackground = TextPrimary,
    onSurface = TextPrimary,
    onSurfaceVariant = TextSecondary,

    // Borders
    outline = OutlineColor,
    outlineVariant = DividerColor,

    // Inverse (for snackbars etc)
    inverseSurface = Color(0xFFE5E7EB),
    inverseOnSurface = Color(0xFF1A1A2E),
    inversePrimary = Color(0xFF92400E),

    // Scrim
    scrim = Color.Black.copy(alpha = 0.6f)
)

private val TetherShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(24.dp)
)

@Composable
fun TetherTheme(content: @Composable () -> Unit) {
    val colorScheme = TetherColorScheme

    // Set system bar colors to match the dark theme
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = DarkBackground.toArgb()
            window.navigationBarColor = DarkBackground.toArgb()
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = false
                isAppearanceLightNavigationBars = false
            }
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = TetherTypography,
        shapes = TetherShapes,
        content = content
    )
}
