package com.labsage.app.ui.theme

import androidx.compose.ui.graphics.Color

// ============================================================
// LabSage — "Sage" design language
// A refined emerald/teal palette on warm neutrals.
// Light = airy paper; Dark = deep graphite with a green undertone.
// ============================================================

// ---------- Brand ramp (shared accent anchor) ----------
val Brand50 = Color(0xFFECFDF5)
val Brand100 = Color(0xFFD1FAE5)
val Brand200 = Color(0xFFA7F3D0)
val Brand300 = Color(0xFF6EE7B7)
val Brand400 = Color(0xFF34D399)
val Brand500 = Color(0xFF10B981)
val Brand600 = Color(0xFF0D9488) // teal-600 — primary identity
val Brand700 = Color(0xFF0F766E)
val Brand800 = Color(0xFF115E59)
val Brand900 = Color(0xFF134E4A)

// ============================================================
// Light Theme
// ============================================================

val LightPrimary = Brand700
val LightOnPrimary = Color(0xFFFFFFFF)
val LightPrimaryContainer = Color(0xFFCCFBF1)
val LightOnPrimaryContainer = Brand900

val LightSecondary = Color(0xFF475569)
val LightOnSecondary = Color(0xFFFFFFFF)
val LightSecondaryContainer = Color(0xFFE2E8F0)
val LightOnSecondaryContainer = Color(0xFF1E293B)

val LightTertiary = Color(0xFFB45309) // amber — used for "in progress" states
val LightOnTertiary = Color(0xFFFFFFFF)
val LightTertiaryContainer = Color(0xFFFEF3C7)
val LightOnTertiaryContainer = Color(0xFF78350F)

val LightBackground = Color(0xFFF6F8F7)
val LightOnBackground = Color(0xFF0F172A)
val LightSurface = Color(0xFFFFFFFF)
val LightOnSurface = Color(0xFF0F172A)
val LightSurfaceVariant = Color(0xFFEEF2F0)
val LightOnSurfaceVariant = Color(0xFF51646B)

// Elevated surface tiers (M3 surfaceContainer family)
val LightSurfaceContainerLow = Color(0xFFF2F5F3)
val LightSurfaceContainer = Color(0xFFECF0EE)
val LightSurfaceContainerHigh = Color(0xFFE6EBE9)
val LightSurfaceContainerHighest = Color(0xFFDFE5E2)

val LightUserBubble = Brand700
val LightUserBubbleText = Color(0xFFFFFFFF)
val LightAssistantBubble = Color(0xFFF1F4F2)
val LightAssistantBubbleText = Color(0xFF16201D)

val LightTextPrimary = Color(0xFF0F172A)
val LightTextSecondary = Color(0xFF51646B)
val LightTextTertiary = Color(0xFF8A9B97)

val LightError = Color(0xFFDC2626)
val LightOnError = Color(0xFFFFFFFF)
val LightErrorContainer = Color(0xFFFEE2E2)
val LightOnErrorContainer = Color(0xFF7F1D1D)
val LightSuccess = Color(0xFF059669)
val LightWarning = Color(0xFFD97706)

val LightOutline = Color(0xFFCBD5D3)
val LightOutlineVariant = Color(0xFFE2E8E6)
val LightScrim = Color(0xFF000000)

// Extended chat accents
val LightInlineTraceBg = Color(0xFFE7FBF3)
val LightCompletionBg = Color(0xFFE7FBF3)
val LightBrandStart = Brand400 // gradient top
val LightBrandEnd = Brand700 // gradient bottom

// ============================================================
// Dark Theme
// ============================================================

val DarkPrimary = Color(0xFF2DD4BF)
val DarkOnPrimary = Color(0xFF00322B)
val DarkPrimaryContainer = Color(0xFF115E54)
val DarkOnPrimaryContainer = Color(0xFFA7F3D0)

val DarkSecondary = Color(0xFF94A3B8)
val DarkOnSecondary = Color(0xFF141A1F)
val DarkSecondaryContainer = Color(0xFF2C3640)
val DarkOnSecondaryContainer = Color(0xFFE2E8F0)

val DarkTertiary = Color(0xFFFBBF24) // amber-400
val DarkOnTertiary = Color(0xFF422006)
val DarkTertiaryContainer = Color(0xFF6B4A08)
val DarkOnTertiaryContainer = Color(0xFFFDE68A)

val DarkBackground = Color(0xFF0B0F0E)
val DarkOnBackground = Color(0xFFE7ECEA)
val DarkSurface = Color(0xFF121816)
val DarkOnSurface = Color(0xFFE7ECEA)
val DarkSurfaceVariant = Color(0xFF1F2825)
val DarkOnSurfaceVariant = Color(0xFF9FB3AE)

val DarkSurfaceContainerLow = Color(0xFF101614)
val DarkSurfaceContainer = Color(0xFF161D1A)
val DarkSurfaceContainerHigh = Color(0xFF1C2421)
val DarkSurfaceContainerHighest = Color(0xFF222B27)

val DarkUserBubble = Color(0xFF115E54)
val DarkUserBubbleText = Color(0xFFECFDF5)
val DarkAssistantBubble = Color(0xFF1C2421)
val DarkAssistantBubbleText = Color(0xFFE7ECEA)

val DarkTextPrimary = Color(0xFFE7ECEA)
val DarkTextSecondary = Color(0xFF9FB3AE)
val DarkTextTertiary = Color(0xFF6B7C77)

val DarkError = Color(0xFFF87171)
val DarkOnError = Color(0xFF4A0E0E)
val DarkErrorContainer = Color(0xFF7F1D1D)
val DarkOnErrorContainer = Color(0xFFFECACA)
val DarkSuccess = Color(0xFF34D399)
val DarkWarning = Color(0xFFFBBF24)

val DarkOutline = Color(0xFF33403A)
val DarkOutlineVariant = Color(0xFF22302B)
val DarkScrim = Color(0xFF000000)

val DarkInlineTraceBg = Color(0xFF0F3D34)
val DarkCompletionBg = Color(0xFF064E3B)
val DarkBrandStart = Color(0xFF2DD4BF)
val DarkBrandEnd = Color(0xFF0D9488)

// ============================================================
// Legacy aliases — point to light theme (kept for backward compat)
// ============================================================

val Primary = LightPrimary
val PrimaryDark = Brand800
val PrimaryLight = LightPrimaryContainer
val PrimaryContainer = LightPrimaryContainer
val Background = LightBackground
val Surface = LightSurface
val SurfaceVariant = LightSurfaceVariant
val UserBubble = LightUserBubble
val UserBubbleText = LightUserBubbleText
val AssistantBubble = LightAssistantBubble
val AssistantBubbleText = LightAssistantBubbleText
val TextPrimary = LightTextPrimary
val TextSecondary = LightTextSecondary
val TextTertiary = LightTextTertiary
val Error = LightError
val ErrorContainer = LightErrorContainer
val Success = LightSuccess
val Warning = LightWarning
val Divider = LightOutlineVariant
val Border = LightOutline
