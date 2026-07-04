package com.labsage.app.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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

    fun setBaseUrl(url: String) {
        viewModelScope.launch { settingsRepository.setBaseUrl(url) }
    }

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch { settingsRepository.setThemeMode(mode) }
    }

    fun setModel(model: String) {
        viewModelScope.launch { settingsRepository.setModel(model) }
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
     * Ping the given backend URL with a short-timeout GET. Any HTTP response
     * counts as "reachable" (even 404) — a connection refused / timeout counts
     * as failure. Runs on Dispatchers.IO.
     */
    fun testConnection(url: String) {
        viewModelScope.launch {
            _connectionState.value = ConnectionState.Testing
            _connectionState.value = withContext(Dispatchers.IO) { ping(url) }
        }
    }

    fun resetConnectionState() {
        _connectionState.value = ConnectionState.Idle
    }

    private fun ping(rawUrl: String): ConnectionState {
        val base = normalizeUrl(rawUrl)
        val client = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .build()
        val request = Request.Builder().url(base).get().build()
        val startedAt = System.nanoTime()
        return try {
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
        if (u.isEmpty()) u = AppSettings.DEFAULT_BASE_URL
        if (!u.startsWith("http://") && !u.startsWith("https://")) u = "http://$u"
        if (!u.endsWith("/")) u += "/"
        return u
    }
}
