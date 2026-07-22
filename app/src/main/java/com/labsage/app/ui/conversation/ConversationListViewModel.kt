package com.labsage.app.ui.conversation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.labsage.app.data.local.entity.ConversationEntity
import com.labsage.app.data.repository.ChatRepository
import com.labsage.app.data.sync.HistorySyncState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
class ConversationListViewModel(
    private val repository: ChatRepository
) : ViewModel() {

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _syncState = MutableStateFlow(HistorySyncState())
    val syncState: StateFlow<HistorySyncState> = _syncState.asStateFlow()

    /**
     * Conversations staged for deletion are hidden immediately, but remain in
     * Room until the Snackbar timeout finishes. This keeps Undo lossless even
     * though the real delete cascades to every message in the conversation.
     */
    private val pendingDeletionIds = MutableStateFlow<Set<String>>(emptySet())

    /**
     * Switches between the full ordered list (query blank) and title search
     * results (query non-blank). Both preserve pinned-first ordering from the DAO.
     */
    val conversations: StateFlow<List<ConversationEntity>> = _searchQuery
        .flatMapLatest { query ->
            if (query.isBlank()) repository.getAllConversations()
            else repository.searchConversations(query.trim())
        }
        .combine(pendingDeletionIds) { conversations, pendingIds ->
            conversations.filterNot { it.id in pendingIds }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        refreshConversations()
    }

    fun refreshConversations() {
        if (_syncState.value.isSyncing) return
        viewModelScope.launch {
            _syncState.value = HistorySyncState(isSyncing = true)
            val result = repository.syncConversations()
            _syncState.value = result.fold(
                onSuccess = { HistorySyncState() },
                onFailure = { HistorySyncState(errorMessage = it.message ?: "Conversation sync failed") }
            )
        }
    }

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun createNewConversation(): String {
        val id = UUID.randomUUID().toString()
        return id
    }

    fun stageDeleteConversation(id: String) {
        pendingDeletionIds.update { it + id }
    }

    fun undoDeleteConversation(id: String) {
        pendingDeletionIds.update { it - id }
    }

    fun commitDeleteConversation(id: String) {
        viewModelScope.launch {
            try {
                repository.deleteConversation(id)
            } finally {
                pendingDeletionIds.update { it - id }
            }
        }
    }

    fun togglePin(conversation: ConversationEntity) {
        viewModelScope.launch {
            repository.setConversationPinned(conversation.id, !conversation.isPinned)
        }
    }

    fun rename(id: String, newTitle: String) {
        if (newTitle.isBlank()) return
        viewModelScope.launch { repository.renameConversation(id, newTitle) }
    }
}
