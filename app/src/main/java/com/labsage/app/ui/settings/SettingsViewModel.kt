package com.labsage.app.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.labsage.app.BuildConfig
import com.labsage.app.data.repository.SettingsRepository
import com.labsage.app.domain.model.AppSettings
import com.labsage.app.domain.model.QuickAction
import com.labsage.app.domain.model.ThemeMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

class SettingsViewModel(
    private val settingsRepository: SettingsRepository
) : ViewModel() {

    val settings: StateFlow<AppSettings> = settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AppSettings())

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch { settingsRepository.setThemeMode(mode) }
    }

    fun setApprovalMode(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setApprovalMode(enabled) }
    }

    /** Toggle a quick-action chip in the input bar. */
    fun toggleQuickAction(action: QuickAction) {
        val current = settings.value.quickActions.toMutableList()
        if (!current.remove(action)) current.add(action)
        viewModelScope.launch { settingsRepository.setQuickActions(current) }
    }

    // ============================================================
    // Connection test
    // ============================================================

    sealed interface ConnectionState {
        data object Idle : ConnectionState
        data object Testing : ConnectionState
        data class Ok(val latencyMs: Long) : ConnectionState
        data class Failed(val message: String) : ConnectionState
    }

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    /**
     * Ping the fixed backend (BuildConfig.API_BASE_URL) with a short-timeout GET.
     * Any HTTP response counts as "reachable" (even 404) — a connection refused /
     * timeout counts as failure. Runs on Dispatchers.IO.
     */
    fun testConnection() {
        viewModelScope.launch {
            _connectionState.value = ConnectionState.Testing
            _connectionState.value = withContext(Dispatchers.IO) { ping(BuildConfig.API_BASE_URL) }
        }
    }

    private fun ping(rawUrl: String): ConnectionState {
        val client = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .build()
        val startedAt = System.nanoTime()
        // The whole body is wrapped — Request.Builder().url() throws an
        // unchecked IllegalArgumentException for malformed URLs (bad port,
        // bad scheme, stray spaces) BEFORE execute(), and that must surface
        // as a Failed state, not crash the coroutine/app.
        return try {
            val base = normalizeUrl(rawUrl)
            val request = Request.Builder().url(base).get().build()
            client.newCall(request).execute().use { response ->
                val latencyMs = (System.nanoTime() - startedAt) / 1_000_000
                // Any HTTP status means the server answered → reachable.
                ConnectionState.Ok(latencyMs)
            }
        } catch (e: Exception) {
            ConnectionState.Failed(e.message ?: "Connection failed")
        }
    }

    private fun normalizeUrl(raw: String): String {
        var u = raw.trim()
        if (u.isEmpty()) u = BuildConfig.API_BASE_URL
        if (!u.startsWith("http://") && !u.startsWith("https://")) u = "http://$u"
        if (!u.endsWith("/")) u += "/"
        return u
    }
}
