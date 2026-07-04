package com.labsage.app.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.labsage.app.domain.model.AppSettings
import com.labsage.app.domain.model.QuickAction
import com.labsage.app.domain.model.ThemeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

/**
 * Single DataStore-backed preferences store for user settings.
 *
 * DataStore (Preferences variant) is the right tool here: small, typed,
 * key-value config that must survive process death and be observable.
 * Room would be overkill for a flat settings record.
 */
private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "labsage_settings")

class SettingsRepository(private val context: Context) {

    private object Keys {
        val BASE_URL = stringPreferencesKey("base_url")
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val MODEL = stringPreferencesKey("model")
        val APPROVAL_MODE = booleanPreferencesKey("approval_mode")
        val QUICK_ACTIONS = stringPreferencesKey("quick_actions")
    }

    val settings: Flow<AppSettings> = context.settingsDataStore.data
        .catch { e ->
            // DataStore read errors (e.g. corrupted file) → fall back to defaults
            // instead of crashing. The store self-heals on the next write.
            if (e is IOException) emit(emptyPreferences()) else throw e
        }
        .map { prefs ->
            AppSettings(
                baseUrl = prefs[Keys.BASE_URL]?.takeIf { it.isNotBlank() }
                    ?: AppSettings.DEFAULT_BASE_URL,
                themeMode = ThemeMode.fromString(prefs[Keys.THEME_MODE]),
                model = prefs[Keys.MODEL]?.takeIf { it.isNotBlank() }
                    ?: AppSettings.DEFAULT_MODEL,
                approvalMode = prefs[Keys.APPROVAL_MODE] ?: false,
                quickActions = QuickAction.fromKeys(prefs[Keys.QUICK_ACTIONS])
            )
        }

    suspend fun setBaseUrl(url: String) {
        context.settingsDataStore.edit { it[Keys.BASE_URL] = url.trim() }
    }

    suspend fun setThemeMode(mode: ThemeMode) {
        context.settingsDataStore.edit { it[Keys.THEME_MODE] = mode.name }
    }

    suspend fun setModel(model: String) {
        context.settingsDataStore.edit { it[Keys.MODEL] = model }
    }

    suspend fun setApprovalMode(enabled: Boolean) {
        context.settingsDataStore.edit { it[Keys.APPROVAL_MODE] = enabled }
    }

    suspend fun setQuickActions(actions: List<QuickAction>) {
        context.settingsDataStore.edit {
            it[Keys.QUICK_ACTIONS] = actions.joinToString(",") { a -> a.key }
        }
    }
}
