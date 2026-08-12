package com.labsage.app.ui.files

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.labsage.app.data.remote.KnowledgeFileDto
import com.labsage.app.data.repository.ChatRepository
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

/**
 * ViewModel for the file / knowledge-base management page.
 *
 * **Remote-first**: the backend is the single source of truth — there is no
 * local Room table of files in v1 (see `docs/files-api-contract.md`). The list
 * lives in an in-memory StateFlow and is re-fetched on load / refresh / retry,
 * mirroring the network-state-machine pattern used by `SettingsViewModel`
 * (connection test) and `UploadState` in `ChatViewModel`.
 *
 * **Optimistic delete**: the row is removed from the local list immediately so
 * the UI feels instant, the network call runs afterward, and on failure the row
 * is rolled back and an error event is emitted. No "undo" is offered — a real
 * backend delete cannot be reversed (the file content lives only server-side).
 */
class FileManagementViewModel(
    private val repository: ChatRepository
) : ViewModel() {

    sealed interface UiState {
        data object Loading : UiState
        data class Success(val files: List<KnowledgeFileDto>) : UiState
        data class Error(val message: String) : UiState
    }

    /** One-shot UI events surfaced as snackbars (not part of [UiState]). */
    sealed interface UiEvent {
        data class DeleteFailed(val fileName: String) : UiEvent
        data class LoadFailed(val message: String) : UiEvent
    }

    private val _state = MutableStateFlow<UiState>(UiState.Loading)
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    /**
     * True while a background [refresh] is in flight. Unlike the initial [load],
     * a refresh keeps the current list visible, so this flag only drives the
     * indicator (button spinner + pull-to-refresh thumb), never a full Loading
     * screen.
     */
    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    private val _events = Channel<UiEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    init {
        load()
    }

    /**
     * Initial / retry load: replace the whole screen with a full Loading state.
     * Use this when there is no useful list to keep (first open, or after an
     * error — retry should show Loading, not silently swap).
     */
    fun load() {
        _state.value = UiState.Loading
        fetch(replaceOnError = true)
    }

    fun retry() = load()

    /**
     * Background refresh: keep the current list visible and only flip the
     * [refreshing] indicator. On failure the list is kept and a [UiEvent.LoadFailed]
     * snackbar fires — never wipe data the user is already looking at.
     *
     * No-op while a refresh is already running; falls back to a full [load] when
     * there is no list yet (e.g. the screen is in Error).
     */
    fun refresh() {
        if (_refreshing.value) return
        if (_state.value !is UiState.Success) {
            load()
            return
        }
        _refreshing.value = true
        fetch(replaceOnError = false) { _refreshing.value = false }
    }

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    private fun fetch(replaceOnError: Boolean, onFinally: (() -> Unit)? = null) {
        viewModelScope.launch {
            try {
                repository.listFiles()
                    .onSuccess { files -> _state.value = UiState.Success(files) }
                    .onFailure { e ->
                        val msg = e.message ?: "加载失败"
                        if (replaceOnError) {
                            _state.value = UiState.Error(msg)
                        } else {
                            _events.send(UiEvent.LoadFailed(msg))
                        }
                    }
            } finally {
                onFinally?.invoke()
            }
        }
    }

    /**
     * Optimistic delete: drop the row locally now, call the backend, roll back
     * and notify on failure. [viewModelScope] runs on the main dispatcher, so the
     * synchronous state mutation before the suspending network call is race-free.
     */
    fun delete(file: KnowledgeFileDto) {
        if (file.id.isBlank()) return // no stable handle — cannot delete

        val current = (_state.value as? UiState.Success)?.files ?: return
        _state.value = UiState.Success(current.filterNot { it.id == file.id })

        viewModelScope.launch {
            repository.deleteFile(file.id)
                .onFailure {
                    // Rollback only if not already re-added by a concurrent retry.
                    val after = (_state.value as? UiState.Success)?.files ?: current
                    if (after.none { it.id == file.id }) {
                        _state.value = UiState.Success(
                            (after + file).sortedByDescending { it.uploadedAt }
                        )
                    }
                    _events.send(UiEvent.DeleteFailed(file.fileName))
                }
        }
    }
}
