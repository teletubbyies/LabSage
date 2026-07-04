package com.labsage.app.ui.chat

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.labsage.app.data.local.entity.MessageEntity
import com.labsage.app.data.remote.SseEvent
import com.labsage.app.data.remote.UploadData
import com.labsage.app.data.repository.ChatRepository
import com.labsage.app.data.repository.SettingsRepository
import com.labsage.app.domain.model.StreamState
import com.labsage.app.domain.model.ToolCallStatus
import com.labsage.app.domain.model.ToolCallUi
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
    private val settingsRepository: SettingsRepository,
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

    /** Brief completion indicator — true for 2s after Done event */
    private val _justCompleted = MutableStateFlow(false)
    val justCompleted: StateFlow<Boolean> = _justCompleted.asStateFlow()

    /**
     * Structured tool calls for the current/last stream — drives the expandable
     * execution panel. Survives past Done (until the next send) so the user can
     * inspect what ran after a response completes.
     */
    private val _toolCalls = MutableStateFlow<List<ToolCallUi>>(emptyList())
    val toolCalls: StateFlow<List<ToolCallUi>> = _toolCalls.asStateFlow()

    /** Route (direct/react) of the current/last stream. */
    private val _activeRoute = MutableStateFlow<String?>(null)
    val activeRoute: StateFlow<String?> = _activeRoute.asStateFlow()

    // ============================================================
    // Approval mode — client-side pre-send gate (human-in-the-loop)
    // ============================================================

    /**
     * When true, [sendMessage] / quick actions do not execute immediately;
     * instead the question is staged in [_pendingSend] and the UI shows a
     * confirmation dialog. Genuine mid-stream interception would require the
     * backend to pause on each tool call — this gate covers the common case of
     * confirming a tool-bearing query before it leaves the device.
     */
    private val _approvalMode = MutableStateFlow(false)
    val approvalMode: StateFlow<Boolean> = _approvalMode.asStateFlow()

    fun setApprovalMode(enabled: Boolean) {
        // Persist via DataStore; the collector below keeps _approvalMode in sync.
        viewModelScope.launch { settingsRepository.setApprovalMode(enabled) }
    }

    /** Question awaiting user approval (non-null → dialog visible). */
    private val _pendingSend = MutableStateFlow<String?>(null)
    val pendingSend: StateFlow<String?> = _pendingSend.asStateFlow()

    fun confirmPendingSend() {
        _pendingSend.value?.let { doSend(it) }
        _pendingSend.value = null
    }

    fun cancelPendingSend() {
        _pendingSend.value = null
    }

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
    private var chunkCount = 0  // throttle Room writes
    /** Per-tool-call start time (key → nanoTime) for duration measurement. */
    private val toolCallStartNanos = mutableMapOf<String, Long>()

    init {
        // Observe Room messages — conflate to skip intermediate states
        // during rapid streaming updates (reduces recomposition from
        // ~50/sec to ~10/sec without visible content loss)
        viewModelScope.launch {
            repository.getMessages(conversationId)
                .conflate()  // drop intermediate emissions when consumer is slow
                .collect { entities ->
                    _messages.value = entities.map { it.toUiState() }
                }
        }
        // Sync approval mode from persisted settings (single source of truth).
        viewModelScope.launch {
            settingsRepository.settings.collect { s ->
                _approvalMode.value = s.approvalMode
            }
        }
    }

    // ============================================================
    // Send message (SSE streaming)
    // ============================================================

    fun sendMessage(question: String) {
        if (_streamState.value is StreamState.Streaming) return
        // Approval gate: stage the question for the UI to confirm.
        if (_approvalMode.value) {
            _pendingSend.value = question
            return
        }
        doSend(question)
    }

    private fun doSend(question: String) {
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
            _toolCalls.value = emptyList()
            _activeRoute.value = null
            toolCallStartNanos.clear()
            currentRoute = null
            streamingMessageId = null
            chunkCount = 0

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
                chunkCount++

                if (streamingMessageId == null) {
                    streamingMessageId = repository.saveAssistantMessage(
                        conversationId = conversationId,
                        content = accumulatedAnswer.toString(),
                        isPartial = true,
                        originalQuestion = lastQuestion
                    )
                } else if (chunkCount % 5 == 0) {
                    // Batch: write to Room every 5th chunk (reduces disk I/O
                    // backpressure; final content always flushed on Done)
                    repository.updateAssistantMessage(
                        streamingMessageId!!,
                        accumulatedAnswer.toString(),
                        isPartial = true
                    )
                }
            }

            is SseEvent.Trace -> {
                accumulatedTraces.add(event.line)
                // Only keep last 5 traces for inline display (avoid flooding)
                _traceLines.value = accumulatedTraces.takeLast(5)
                // Build structured tool-call timeline for the execution panel.
                applyToolTrace(event.line)
            }

            is SseEvent.TraceSummary -> {
                currentToolStats = event.toolStats.toMutableMap()
                _toolStats.value = event.toolStats
                // Show tool execution status via traceLines (inline chip reads this)
                if (event.toolStats.isNotEmpty()) {
                    val toolNames = event.toolStats.keys.joinToString(", ")
                    _traceLines.value = listOf("[tool] 调用完成 $toolNames")
                }
                currentRoute = accumulatedTraces
                    .find { it.startsWith("[route]") || it.contains("route") }
                    ?.let { line ->
                        if (line.contains("direct")) "direct"
                        else if (line.contains("react")) "react"
                        else null
                    }
                if (currentRoute != null) _activeRoute.value = currentRoute
            }

            is SseEvent.Done -> {
                val finalAnswer = accumulatedAnswer.toString()
                streamingMessageId?.let { id ->
                    // Flush final content to Room (covers unbatch remainder)
                    repository.finalizeAssistantMessage(id, finalAnswer, currentRoute)
                }
                streamingMessageId = null
                _streamState.value = StreamState.Completed(
                    fullAnswer = finalAnswer,
                    route = currentRoute,
                    toolStats = currentToolStats
                )
                // Any tool still CALLING when the stream ends did not finish.
                if (_toolCalls.value.any { it.status == ToolCallStatus.CALLING }) {
                    _toolCalls.value = _toolCalls.value.map {
                        if (it.status == ToolCallStatus.CALLING) it.copy(status = ToolCallStatus.ERROR)
                        else it
                    }
                }
                _activeRoute.value = currentRoute
                _traceLines.value = emptyList()
                // Show completion indicator briefly
                _justCompleted.value = true
                kotlinx.coroutines.delay(2500)
                _justCompleted.value = false
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

    /**
     * Parse one structured trace line into the [_toolCalls] timeline and update
     * the live [_activeRoute]. Recognized shapes (from the Go backend):
     *   `[tool] 开始调用 #N <name> args=<json>`
     *   `[tool] 调用完成 <name> result=<json>`
     *   `[route] route=<direct|react> intent=...`
     */
    private fun applyToolTrace(line: String) {
        when {
            line.startsWith("[tool]") && line.contains("开始调用") -> {
                val callNo = Regex("""#(\d+)""").find(line)?.groupValues?.get(1)?.toIntOrNull()
                    ?: (_toolCalls.value.size + 1)
                val afterStart = line.substringAfter("开始调用", "").trim()
                val name = afterStart.substringBefore(" args=").substringBefore(" ").trim()
                    .ifEmpty { "tool" }
                val args = line.substringAfter("args=", "").trim()
                // Unique key: index + name + current count avoids collisions when
                // the same tool is called repeatedly in one stream.
                val key = "tc_${callNo}_${name}_${_toolCalls.value.size}"
                toolCallStartNanos[key] = System.nanoTime()
                _toolCalls.value = _toolCalls.value + ToolCallUi(
                    key = key,
                    index = callNo,
                    name = name,
                    status = ToolCallStatus.CALLING,
                    argsText = args,
                    resultText = "",
                    durationMs = 0
                )
            }

            line.startsWith("[tool]") && line.contains("调用完成") -> {
                val name = line.substringAfter("调用完成", "").trim()
                    .substringBefore(" result=").trim()
                if (name.isEmpty()) return
                val result = line.substringAfter("result=", "").trim()
                // FIFO match: complete the OLDEST still-calling entry with this name.
                val list = _toolCalls.value.toMutableList()
                for (i in list.indices) {
                    val tc = list[i]
                    if (tc.status == ToolCallStatus.CALLING && tc.name == name) {
                        val dur = toolCallStartNanos.remove(tc.key)
                            ?.let { (System.nanoTime() - it) / 1_000_000 } ?: 0
                        list[i] = tc.copy(
                            status = ToolCallStatus.COMPLETED,
                            resultText = result,
                            durationMs = dur
                        )
                        break
                    }
                }
                _toolCalls.value = list
            }

            line.startsWith("[route]") -> {
                val route = when {
                    line.contains("route=react") -> "react"
                    line.contains("route=direct") -> "direct"
                    else -> null
                }
                if (route != null) {
                    currentRoute = route
                    _activeRoute.value = route
                }
            }
        }
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

@androidx.compose.runtime.Immutable
data class SessionContextUi(
    val summary: String,
    val facts: String,
    val isEditing: Boolean
)

@androidx.compose.runtime.Immutable
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
