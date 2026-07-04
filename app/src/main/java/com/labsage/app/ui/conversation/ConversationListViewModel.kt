package com.labsage.app.ui.conversation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.labsage.app.data.local.entity.ConversationEntity
import com.labsage.app.data.repository.ChatRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
class ConversationListViewModel(
    private val repository: ChatRepository
) : ViewModel() {

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    /**
     * Switches between the full ordered list (query blank) and title search
     * results (query non-blank). Both preserve pinned-first ordering from the DAO.
     */
    val conversations: StateFlow<List<ConversationEntity>> = _searchQuery
        .flatMapLatest { query ->
            if (query.isBlank()) repository.getAllConversations()
            else repository.searchConversations(query.trim())
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun createNewConversation(): String {
        val id = "session_${UUID.randomUUID().toString().take(8)}_${System.currentTimeMillis()}"
        return id
    }

    fun deleteConversation(id: String) {
        viewModelScope.launch { repository.deleteConversation(id) }
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
