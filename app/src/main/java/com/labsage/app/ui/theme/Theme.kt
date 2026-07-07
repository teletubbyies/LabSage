package com.labsage.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

// ============================================================
// Extended colors — custom slots not in the Material 3 scheme
// ============================================================

@Immutable
data class ExtendedColors(
    val userBubble: Color,
    val userBubbleOn: Color,
    val assistantBubble: Color,
    val assistantBubbleOn: Color,
    val inlineTraceBg: Color,
    val completionBg: Color,
    val brandStart: Color,
    val brandEnd: Color,
)

val LightExtendedColors = ExtendedColors(
    userBubble = LightUserBubble,
    userBubbleOn = LightUserBubbleText,
    assistantBubble = LightAssistantBubble,
    assistantBubbleOn = LightAssistantBubbleText,
    inlineTraceBg = LightInlineTraceBg,
    completionBg = LightCompletionBg,
    brandStart = LightBrandStart,
    brandEnd = LightBrandEnd,
)

val DarkExtendedColors = ExtendedColors(
    userBubble = DarkUserBubble,
    userBubbleOn = DarkUserBubbleText,
    assistantBubble = DarkAssistantBubble,
    assistantBubbleOn = DarkAssistantBubbleText,
    inlineTraceBg = DarkInlineTraceBg,
    completionBg = DarkCompletionBg,
    brandStart = DarkBrandStart,
    brandEnd = DarkBrandEnd,
)

val LocalExtendedColors = staticCompositionLocalOf { LightExtendedColors }

/** The LabSage brand gradient as a [Brush] (emerald → teal). */
@Composable
fun brandBrush(): Brush =
    Brush.linearGradient(listOf(LocalExtendedColors.current.brandStart, LocalExtendedColors.current.brandEnd))

// ============================================================
// Material 3 Color Schemes
// ============================================================

private val LightColorScheme = lightColorScheme(
    primary = LightPrimary,
    onPrimary = LightOnPrimary,
    primaryContainer = LightPrimaryContainer,
    onPrimaryContainer = LightOnPrimaryContainer,
    secondary = LightSecondary,
    onSecondary = LightOnSecondary,
    secondaryContainer = LightSecondaryContainer,
    onSecondaryContainer = LightOnSecondaryContainer,
    tertiary = LightTertiary,
    onTertiary = LightOnTertiary,
    tertiaryContainer = LightTertiaryContainer,
    onTertiaryContainer = LightOnTertiaryContainer,
    background = LightBackground,
    onBackground = LightOnBackground,
    surface = LightSurface,
    onSurface = LightOnSurface,
    surfaceVariant = LightSurfaceVariant,
    onSurfaceVariant = LightOnSurfaceVariant,
    surfaceTint = LightPrimary,
    inverseSurface = Color(0xFF2A3330),
    inverseOnSurface = Color(0xFFEFF1EF),
    inversePrimary = Brand300,
    outline = LightOutline,
    outlineVariant = LightOutlineVariant,
    scrim = LightScrim,
    error = LightError,
    onError = LightOnError,
    errorContainer = LightErrorContainer,
    onErrorContainer = LightOnErrorContainer,
)

private val DarkColorScheme = darkColorScheme(
    primary = DarkPrimary,
    onPrimary = DarkOnPrimary,
    primaryContainer = DarkPrimaryContainer,
    onPrimaryContainer = DarkOnPrimaryContainer,
    secondary = DarkSecondary,
    onSecondary = DarkOnSecondary,
    secondaryContainer = DarkSecondaryContainer,
    onSecondaryContainer = DarkOnSecondaryContainer,
    tertiary = DarkTertiary,
    onTertiary = DarkOnTertiary,
    tertiaryContainer = DarkTertiaryContainer,
    onTertiaryContainer = DarkOnTertiaryContainer,
    background = DarkBackground,
    onBackground = DarkOnBackground,
    surface = DarkSurface,
    onSurface = DarkOnSurface,
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = DarkOnSurfaceVariant,
    surfaceTint = DarkPrimary,
    inverseSurface = Color(0xFFE7ECEA),
    inverseOnSurface = Color(0xFF1A201E),
    inversePrimary = Brand700,
    outline = DarkOutline,
    outlineVariant = DarkOutlineVariant,
    scrim = DarkScrim,
    error = DarkError,
    onError = DarkOnError,
    errorContainer = DarkErrorContainer,
    onErrorContainer = DarkOnErrorContainer,
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
            shapes = Shapes,
            content = content
        )
    }
}
