package com.labsage.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color

// ============================================================
// Extended colors — custom slots not in Material 3 scheme
// ============================================================

@Immutable
data class ExtendedColors(
    val userBubble: Color,
    val userBubbleOn: Color,
    val assistantBubble: Color,
    val assistantBubbleOn: Color,
    val inlineTraceBg: Color,
    val completionBg: Color,
)

val LightExtendedColors = ExtendedColors(
    userBubble = LightUserBubble,
    userBubbleOn = LightUserBubbleText,
    assistantBubble = LightAssistantBubble,
    assistantBubbleOn = LightAssistantBubbleText,
    inlineTraceBg = LightInlineTraceBg,
    completionBg = LightCompletionBg,
)

val DarkExtendedColors = ExtendedColors(
    userBubble = DarkUserBubble,
    userBubbleOn = DarkUserBubbleText,
    assistantBubble = DarkAssistantBubble,
    assistantBubbleOn = DarkAssistantBubbleText,
    inlineTraceBg = DarkInlineTraceBg,
    completionBg = DarkCompletionBg,
)

val LocalExtendedColors = staticCompositionLocalOf { LightExtendedColors }

// ============================================================
// Material 3 Color Schemes
// ============================================================

private val LightColorScheme = lightColorScheme(
    primary = LightPrimary,
    onPrimary = Color.White,
    primaryContainer = LightPrimaryContainer,
    onPrimaryContainer = LightOnPrimaryContainer,
    secondary = LightPrimaryDark,
    background = LightBackground,
    surface = LightSurface,
    surfaceVariant = LightSurfaceVariant,
    onBackground = LightTextPrimary,
    onSurface = LightTextPrimary,
    onSurfaceVariant = LightTextSecondary,
    error = LightError,
    errorContainer = LightErrorContainer,
    outline = LightBorder,
    outlineVariant = LightDivider,
)

private val DarkColorScheme = darkColorScheme(
    primary = DarkPrimary,
    onPrimary = Color.White,
    primaryContainer = DarkPrimaryContainer,
    onPrimaryContainer = DarkOnPrimaryContainer,
    secondary = DarkPrimaryDark,
    background = DarkBackground,
    surface = DarkSurface,
    surfaceVariant = DarkSurfaceVariant,
    onBackground = DarkTextPrimary,
    onSurface = DarkTextPrimary,
    onSurfaceVariant = DarkTextSecondary,
    error = DarkError,
    errorContainer = DarkErrorContainer,
    outline = DarkBorder,
    outlineVariant = DarkDivider,
)

// ============================================================
// Theme entry point
// ============================================================

@Composable
fun LabSageTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
    val extendedColors = if (darkTheme) DarkExtendedColors else LightExtendedColors

    CompositionLocalProvider(LocalExtendedColors provides extendedColors) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography,
            content = content
        )
    }
}
