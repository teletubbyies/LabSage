package com.labsage.app.ui.conversation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.labsage.app.data.local.entity.ConversationEntity
import com.labsage.app.data.repository.ChatRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID

class ConversationListViewModel(
    private val repository: ChatRepository
) : ViewModel() {

    val conversations: StateFlow<List<ConversationEntity>> = repository
        .getAllConversations()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun createNewConversation(): String {
        val id = "session_${UUID.randomUUID().toString().take(8)}_${System.currentTimeMillis()}"
        return id
    }

    fun deleteConversation(id: String) {
        viewModelScope.launch {
            repository.deleteConversation(id)
        }
    }
}
