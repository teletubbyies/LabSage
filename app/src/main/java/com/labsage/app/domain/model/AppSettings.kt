package com.labsage.app.domain.model

import androidx.compose.runtime.Immutable

/**
 * Manual theme preference. SYSTEM follows the platform dark-mode setting.
 */
enum class ThemeMode {
    SYSTEM, LIGHT, DARK;

    companion object {
        fun fromString(value: String?): ThemeMode =
            entries.firstOrNull { it.name == value } ?: SYSTEM
    }
}

/**
 * Known quick-action chips the input bar can surface.
 * Stored as a comma-joined string in DataStore.
 */
enum class QuickAction(val key: String) {
    GPU("GPU"),
    CONDA("Conda");

    companion object {
        fun fromKeys(joined: String?): List<QuickAction> {
            if (joined.isNullOrBlank()) return entries
            return joined.split(",")
                .mapNotNull { raw -> entries.firstOrNull { it.key.equals(raw.trim(), ignoreCase = true) } }
                .ifEmpty { entries }
        }
    }
}

/**
 * User-configurable application settings, persisted via DataStore.
 */
@Immutable
data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    /** When true, sending a message prompts for confirmation (tool-call approval gate). */
    val approvalMode: Boolean = false,
    /** Which quick-action chips appear in the input bar. */
    val quickActions: List<QuickAction> = QuickAction.entries
)
