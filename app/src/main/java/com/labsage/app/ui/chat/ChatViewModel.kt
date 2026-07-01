package com.labsage.app.ui.chat

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.labsage.app.data.local.entity.MessageEntity
import com.labsage.app.data.remote.SseEvent
import com.labsage.app.data.remote.UploadData
import com.labsage.app.data.repository.ChatRepository
import com.labsage.app.domain.model.StreamState
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

/**
 * ViewModel for the chat screen.
 *
 * State flow:
 *   1. User sends message → StreamState.Streaming
 *   2. SSE events arrive → accumulate content, update Room, update UI
 *   3a. "done" event → StreamState.Completed
 *   3b. "error" event or exception → StreamState.Error (may be partial/retryable)
 *   4. User clicks "Continue" → send Continue + PartialAnswer → back to Streaming
 *   5. User clicks "Regenerate" → delete partial message, resend original question
 */
class ChatViewModel(
    private val repository: ChatRepository,
    private val conversationId: String
) : ViewModel() {

    // ============================================================
    // UI State (observed by Compose)
    // ============================================================

    private val _messages = MutableStateFlow<List<MessageUiState>>(emptyList())
    val messages: StateFlow<List<MessageUiState>> = _messages.asStateFlow()

    private val _streamState = MutableStateFlow<StreamState>(StreamState.Idle)
    val streamState: StateFlow<StreamState> = _streamState.asStateFlow()

    private val _uploadState = MutableStateFlow<UploadState>(UploadState.Idle)
    val uploadState: StateFlow<UploadState> = _uploadState.asStateFlow()

    /** Live trace lines during SSE streaming — shown in execution panel */
    private val _traceLines = MutableStateFlow<List<String>>(emptyList())
    val traceLines: StateFlow<List<String>> = _traceLines.asStateFlow()

    /** Tool stats from trace_summary event */
    private val _toolStats = MutableStateFlow<Map<String, Int>>(emptyMap())
    val toolStats: StateFlow<Map<String, Int>> = _toolStats.asStateFlow()

    // ============================================================
    // Streaming accumulators
    // ============================================================

    private var streamingJob: Job? = null
    private var streamingMessageId: Long? = null
    private val accumulatedAnswer = StringBuilder()
    private val accumulatedTraces = mutableListOf<String>()
    private var currentToolStats = mutableMapOf<String, Int>()
    private var currentRoute: String? = null
    private var lastQuestion: String = ""

    init {
        // Observe Room messages for this conversation
        viewModelScope.launch {
            repository.getMessages(conversationId).collect { entities ->
                _messages.value = entities.map { it.toUiState() }
            }
        }
    }

    // ============================================================
    // Send message (SSE streaming)
    // ============================================================

    fun sendMessage(question: String) {
        if (_streamState.value is StreamState.Streaming) return
        lastQuestion = question

        viewModelScope.launch {
            // CRITICAL: create conversation BEFORE inserting messages (FK constraint)
            repository.upsertConversation(conversationId, deriveTitle(question))
            // Now persist user message
            repository.saveUserMessage(conversationId, question)

            // Reset stream state
            _streamState.value = StreamState.Streaming
            accumulatedAnswer.clear()
            accumulatedTraces.clear()
            currentToolStats = mutableMapOf<String, Int>()
            _traceLines.value = emptyList()
            _toolStats.value = emptyMap()
            currentRoute = null
            streamingMessageId = null

            // Open SSE stream
            streamingJob = viewModelScope.launch {
                repository.sendStreamMessage(conversationId, question)
                    .catch { e -> handleStreamException(e) }
                    .collect { event -> handleSseEvent(event) }
            }
        }
    }

    // ============================================================
    // Continue / Regenerate (partial message resume)
    // ============================================================

    fun continueGeneration() {
        val state = _streamState.value
        if (state !is StreamState.Error || !state.retryable) return

        viewModelScope.launch {
            _streamState.value = StreamState.Streaming

            streamingJob = viewModelScope.launch {
                repository.continueStreamMessage(
                    conversationId, state.lastQuestion, state.partialAnswer
                )
                    .catch { e -> handleStreamException(e) }
                    .collect { event -> handleSseEvent(event) }
            }
        }
    }

    fun regenerate() {
        val state = _streamState.value
        if (state !is StreamState.Error) return

        // Delete the partial assistant message
        streamingMessageId?.let { id ->
            viewModelScope.launch { repository.deleteMessage(id) }
        }
        streamingMessageId = null
        accumulatedAnswer.clear()
        _streamState.value = StreamState.Idle

        // Resend
        sendMessage(state.lastQuestion)
    }

    // ============================================================
    // File upload
    // ============================================================

    fun uploadFile(uri: Uri, context: android.content.Context) {
        viewModelScope.launch {
            _uploadState.value = UploadState.Uploading
            val result = repository.uploadFile(uri, context)
            _uploadState.value = result.fold(
                onSuccess = { data ->
                    // Add a system message about the upload
                    repository.saveUserMessage(
                        conversationId,
                        "Uploaded: ${data.fileName} (${formatFileSize(data.fileSize)})"
                    )
                    UploadState.Success(data)
                },
                onFailure = { e ->
                    UploadState.Error(e.message ?: "Upload failed")
                }
            )
        }
    }

    // ============================================================
    // SSE event handler
    // ============================================================

    private suspend fun handleSseEvent(event: SseEvent) {
        when (event) {
            is SseEvent.Connected -> {
                // Connection established — nothing to persist
            }

            is SseEvent.Message -> {
                accumulatedAnswer.append(event.text)

                if (streamingMessageId == null) {
                    // First chunk: create placeholder in Room
                    streamingMessageId = repository.saveAssistantMessage(
                        conversationId = conversationId,
                        content = accumulatedAnswer.toString(),
                        isPartial = true,
                        originalQuestion = lastQuestion
                    )
                } else {
                    // Subsequent chunks: update in-place
                    repository.updateAssistantMessage(
                        streamingMessageId!!,
                        accumulatedAnswer.toString(),
                        isPartial = true
                    )
                }
            }

            is SseEvent.Trace -> {
                accumulatedTraces.add(event.line)
                _traceLines.value = accumulatedTraces.toList()
            }

            is SseEvent.TraceSummary -> {
                currentToolStats = event.toolStats.toMutableMap()
                _toolStats.value = event.toolStats
                // Extract route from trace lines if present
                currentRoute = accumulatedTraces
                    .find { it.startsWith("[route]") || it.contains("route") }
                    ?.let { line ->
                        if (line.contains("direct")) "direct"
                        else if (line.contains("react")) "react"
                        else null
                    }
            }

            is SseEvent.Done -> {
                val finalAnswer = accumulatedAnswer.toString()
                streamingMessageId?.let { id ->
                    repository.finalizeAssistantMessage(id, finalAnswer, currentRoute)
                }
                streamingMessageId = null
                _streamState.value = StreamState.Completed(
                    fullAnswer = finalAnswer,
                    route = currentRoute,
                    toolStats = currentToolStats
                )
                // Clear trace display — answer is complete
                _traceLines.value = emptyList()
            }

            is SseEvent.Error -> {
                val partial = accumulatedAnswer.toString()
                if (event.partial && partial.isNotEmpty()) {
                    streamingMessageId?.let { id ->
                        repository.updateAssistantMessage(id, partial, isPartial = true)
                    }
                    _streamState.value = StreamState.Error(
                        message = event.message,
                        partial = true,
                        retryable = event.retryable,
                        partialAnswer = partial,
                        lastQuestion = lastQuestion
                    )
                } else {
                    streamingMessageId?.let { id ->
                        repository.deleteMessage(id)
                    }
                    streamingMessageId = null
                    _streamState.value = StreamState.Error(
                        message = event.message,
                        retryable = event.retryable
                    )
                }
            }
        }
    }

    private suspend fun handleStreamException(e: Throwable) {
        val partial = accumulatedAnswer.toString()
        if (partial.isNotEmpty()) {
            streamingMessageId?.let { id ->
                repository.updateAssistantMessage(id, partial, isPartial = true)
            }
            _streamState.value = StreamState.Error(
                message = e.message ?: "Connection lost",
                partial = true,
                retryable = true,
                partialAnswer = partial,
                lastQuestion = lastQuestion
            )
        } else {
            _streamState.value = StreamState.Error(
                message = e.message ?: "Send failed",
                retryable = false
            )
        }
    }

    // ============================================================
    // Helpers
    // ============================================================

    private suspend fun deleteMessage(id: Long) {
        repository.deleteMessage(id)
    }

    fun clearError() {
        _streamState.value = StreamState.Idle
    }

    /** Send a pre-built GPU status query */
    fun queryGpuStatus() {
        sendMessage("查询服务器当前NVIDIA GPU使用状态")
    }

    /** Send a pre-built conda env query */
    fun queryCondaEnv() {
        sendMessage("查询当前conda环境使用情况")
    }

    // ============================================================
    // Session context (summary + facts)
    // ============================================================

    private val _sessionContext = MutableStateFlow<SessionContextUi?>(null)
    val sessionContext: StateFlow<SessionContextUi?> = _sessionContext.asStateFlow()

    fun loadSessionContext() {
        viewModelScope.launch {
            val result = repository.getSessionContext(conversationId)
            result.onSuccess { data ->
                _sessionContext.value = SessionContextUi(
                    summary = data.summary,
                    facts = data.facts,
                    isEditing = false
                )
            }.onFailure { e ->
                _sessionContext.value = SessionContextUi(
                    summary = "Failed to load: ${e.message}",
                    facts = "",
                    isEditing = false
                )
            }
        }
    }

    fun updateSessionSummary(newSummary: String) {
        viewModelScope.launch {
            val result = repository.updateSessionSummary(conversationId, newSummary)
            result.onSuccess {
                _sessionContext.value = null  // dismiss dialog on success
            }.onFailure { e ->
                _sessionContext.value = _sessionContext.value?.copy(
                    summary = "Save failed: ${e.message}\n\n$newSummary"
                )
            }
        }
    }

    fun dismissSessionContext() {
        _sessionContext.value = null
    }

    private fun deriveTitle(question: String): String {
        return if (question.length <= 30) question else question.take(30) + "..."
    }

    private fun formatFileSize(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "${bytes / 1024} KB"
            else -> "${"%.1f".format(bytes.toDouble() / (1024 * 1024))} MB"
        }
    }

    // ============================================================
    // Extension: MessageEntity → UI state
    // ============================================================

    private fun MessageEntity.toUiState() = MessageUiState(
        id = id,
        role = role,
        content = content,
        timestamp = timestamp,
        isPartial = isPartial,
        route = route,
        originalQuestion = originalQuestion,
        errorMessage = errorMessage
    )
}

// ============================================================
// UI state models
// ============================================================

data class SessionContextUi(
    val summary: String,
    val facts: String,
    val isEditing: Boolean
)

data class MessageUiState(
    val id: Long,
    val role: String,
    val content: String,
    val timestamp: Long,
    val isPartial: Boolean = false,
    val route: String? = null,
    val originalQuestion: String? = null,
    val errorMessage: String? = null
)

sealed class UploadState {
    data object Idle : UploadState()
    data object Uploading : UploadState()
    data class Success(val data: UploadData) : UploadState()
    data class Error(val message: String) : UploadState()
}
