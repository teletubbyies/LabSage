package com.labsage.app.ui.chat

import android.net.Uri
import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.labsage.app.data.local.entity.MessageEntity
import com.labsage.app.data.remote.SseEvent
import com.labsage.app.data.remote.UploadData
import com.labsage.app.data.repository.ChatRepository
import com.labsage.app.data.sync.HistorySyncState
import com.labsage.app.data.repository.SettingsRepository
import com.labsage.app.domain.model.StreamState
import com.labsage.app.domain.model.ToolCallStatus
import com.labsage.app.domain.model.ToolCallUi
import com.labsage.app.notification.ResponseNotificationManager
import com.labsage.app.util.formatFileSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.util.UUID

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
    private val responseNotificationManager: ResponseNotificationManager,
    private val conversationId: String
) : ViewModel() {

    // ============================================================
    // UI State (observed by Compose)
    // ============================================================

    // used in class inside， unseen outside.
    private val _messages = MutableStateFlow<List<MessageUiState>>(emptyList())
    val messages: StateFlow<List<MessageUiState>> = _messages.asStateFlow()

    private val _streamState = MutableStateFlow<StreamState>(StreamState.Idle)
    val streamState: StateFlow<StreamState> = _streamState.asStateFlow()

    private val _uploadState = MutableStateFlow<UploadState>(UploadState.Idle)
    val uploadState: StateFlow<UploadState> = _uploadState.asStateFlow()

    private val _historySyncState = MutableStateFlow(HistorySyncState())
    val historySyncState: StateFlow<HistorySyncState> = _historySyncState.asStateFlow()

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
        // Fire-and-forget
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
    private var messagesObservationJob: Job? = null
    private var streamingMessageId: String? = null
    private var currentUserMessageId: String? = null
    private var currentClientRequestId: String? = null
    private var currentClientCreatedAt: Long = 0
    private val accumulatedAnswer = StringBuilder()
    private val accumulatedTraces = mutableListOf<String>()
    private var currentToolStats = mutableMapOf<String, Int>()
    private var currentRoute: String? = null
    private var lastQuestion: String = ""
    private val sseUpdateCadence = SseUpdateCadence()
    /** Per-tool-call start time (key → nanoTime) for duration measurement. */
    private val toolCallStartNanos = mutableMapOf<String, Long>()

    init {
        startMessageObservation()
        // Sync approval mode from persisted settings (single source of truth).
        viewModelScope.launch {
            settingsRepository.settings.collect { s ->
                _approvalMode.value = s.approvalMode
            }
        }
        refreshMessages()
    }

    /**
     * Room remains the source of truth while the conversation is idle. During an
     * active SSE response this observer is cancelled: UI snapshots come from memory
     * and Room checkpoints no longer cause this full-conversation query to rerun.
     * Restarting this cold Flow after a terminal event performs one authoritative
     * reconciliation query.
     */
    private fun startMessageObservation() {
        if (messagesObservationJob?.isActive == true) return
        messagesObservationJob = viewModelScope.launch {
            repository.getMessages(conversationId)
                .conflate()
                .collect { entities ->
                    _messages.value = entities.map { it.toUiState() }
                    // Rehydrate resume state from a persisted partial reply — see
                    // [restorePartialStateIfAny]. Force-closing the app mid-stream
                    // kills the process and wipes in-memory _streamState, yet the
                    // partial message is checkpointed to Room during streaming.
                    // Without this the "继续生成" banner never reappears on reopen,
                    // even though the partial content survived.
                    restorePartialStateIfAny(entities)
                }
        }
    }

    private suspend fun pauseMessageObservation() {
        val activeObserver = messagesObservationJob
        messagesObservationJob = null
        activeObserver?.cancelAndJoin()
    }

    fun refreshMessages() {
        if (_historySyncState.value.isSyncing) return
        viewModelScope.launch {
            _historySyncState.value = HistorySyncState(isSyncing = true)
            val result = repository.syncMessages(conversationId)
            _historySyncState.value = result.fold(
                onSuccess = { HistorySyncState() },
                onFailure = { HistorySyncState(errorMessage = it.message ?: "Message sync failed") }
            )
        }
    }

    // ============================================================
    // Send message (SSE streaming)
    // ============================================================

    fun sendMessage(question: String) {
        // no resending
        if (_streamState.value is StreamState.Streaming) return
        // Approval gate: stage the question for the UI to confirm.
        if (_approvalMode.value) {
            _pendingSend.value = question
            return
        }
        doSend(question)
    }

    private fun doSend(question: String) {
        // send and collect response
        if (_streamState.value is StreamState.Streaming) return
        lastQuestion = question

        viewModelScope.launch {
            try {
                // CRITICAL(checked): create conversation BEFORE inserting messages (Foreign Key constraint in db)
                repository.upsertConversation(conversationId, deriveTitle(question))
                // Now persist user message
                currentUserMessageId = UUID.randomUUID().toString()
                currentClientRequestId = UUID.randomUUID().toString()
                currentClientCreatedAt = System.currentTimeMillis()
                repository.saveUserMessage(
                    conversationId = conversationId,
                    content = question,
                    messageId = checkNotNull(currentUserMessageId),
                    clientRequestId = checkNotNull(currentClientRequestId),
                    clientCreatedAt = currentClientCreatedAt
                )
                publishUserMessage(
                    messageId = checkNotNull(currentUserMessageId),
                    content = question,
                    timestamp = currentClientCreatedAt
                )
                // Wait until Room collection is fully cancelled before any SSE
                // checkpoint writes can invalidate and rerun the messages query.
                pauseMessageObservation()
            } catch (e: Exception) {
                _streamState.value = StreamState.Error(
                    message = e.message ?: "Unable to write this conversation",
                    retryable = false,
                    lastQuestion = question
                )
                return@launch
            }

            // Reset stream state
            _streamState.value = StreamState.Streaming
            accumulatedAnswer.clear()
            accumulatedTraces.clear()
            currentToolStats = mutableMapOf()
            _traceLines.value = emptyList()
            _toolStats.value = emptyMap()
            _toolCalls.value = emptyList()
            _activeRoute.value = null
            toolCallStartNanos.clear()
            currentRoute = null
            streamingMessageId = null
            sseUpdateCadence.reset(android.os.SystemClock.elapsedRealtime())

            // Open SSE stream
            // data from network io, including chat data stored in db, and tool chain usage stored in memory.
            // streamingJob is under viewModelScope not under before coroutine
            streamingJob = viewModelScope.launch {
                try {
                    // including send and receive
                    repository.sendStreamMessage(
                        sessionId = conversationId,
                        question = question,
                        clientMessageId = checkNotNull(currentUserMessageId),
                        clientRequestId = checkNotNull(currentClientRequestId),
                        clientCreatedAt = currentClientCreatedAt
                    )
                        .catch { e -> handleStreamException(e) }
                        .collect { event -> handleSseEvent(event) }
                } finally {
                    try {
                        // A clean EOF without Done is still a failed terminal path;
                        // persist the latest in-memory answer before reconciling.
                        if (
                            kotlinx.coroutines.currentCoroutineContext()[Job]?.isActive == true &&
                            _streamState.value is StreamState.Streaming
                        ) {
                            persistTerminalFailure(
                                message = "Connection closed before completion",
                                retryable = true
                            )
                        }
                    } finally {
                        // Covers cancellation or a failure while processing/persisting
                        // the terminal event.
                        startMessageObservation()
                    }
                }
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
            val userMessageId = currentUserMessageId ?: return@launch
            val requestId = currentClientRequestId ?: return@launch
            pauseMessageObservation()
            try {
                _streamState.value = StreamState.Streaming
                sseUpdateCadence.reset(android.os.SystemClock.elapsedRealtime())
                streamingMessageId?.let { repository.markAssistantStreaming(it) }
            } catch (error: kotlinx.coroutines.CancellationException) {
                startMessageObservation()
                throw error
            } catch (error: Exception) {
                startMessageObservation()
                _streamState.value = state
                return@launch
            }

            streamingJob = viewModelScope.launch {
                try {
                    repository.continueStreamMessage(
                        sessionId = conversationId,
                        question = state.lastQuestion,
                        partialAnswer = state.partialAnswer,
                        clientMessageId = userMessageId,
                        clientRequestId = requestId,
                        clientCreatedAt = currentClientCreatedAt
                    )
                        .catch { e -> handleStreamException(e) }
                        .collect { event -> handleSseEvent(event) }
                } finally {
                    try {
                        if (
                            kotlinx.coroutines.currentCoroutineContext()[Job]?.isActive == true &&
                            _streamState.value is StreamState.Streaming
                        ) {
                            persistTerminalFailure(
                                message = "Connection closed before completion",
                                retryable = true
                            )
                        }
                    } finally {
                        startMessageObservation()
                    }
                }
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

    fun uploadFile(uri: Uri) {
        if (_uploadState.value is UploadState.Uploading) return
        _uploadState.value = UploadState.Uploading
        viewModelScope.launch {
            val result = repository.uploadFile(uri)
            result.onSuccess { data ->
                // Upload completion is authoritative. A non-critical local chat
                // acknowledgement must not leave the UI stuck in Uploading.
                _uploadState.value = UploadState.Success(data)
                try {
                    // Add a system message about the upload
                    repository.saveUserMessage(
                        conversationId,
                        "Uploaded: ${data.fileName} (${formatFileSize(data.fileSize)})"
                    )
                } catch (error: kotlinx.coroutines.CancellationException) {
                    throw error
                } catch (_: Exception) {
                    // The remote upload succeeded; keep Success even if this
                    // optional local acknowledgement could not be persisted.
                }
            }.onFailure { error ->
                _uploadState.value = UploadState.Error(error.message ?: "Upload failed")
            }
        }
    }

    fun clearUploadState() {
        if (_uploadState.value !is UploadState.Uploading) {
            _uploadState.value = UploadState.Idle
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
                val nowMs = android.os.SystemClock.elapsedRealtime()
                val updateDecision = sseUpdateCadence.onChunk(nowMs)
                val contentSnapshot = if (
                    streamingMessageId == null ||
                    updateDecision.publishUi ||
                    updateDecision.checkpoint
                ) {
                    accumulatedAnswer.toString()
                } else {
                    null
                }
                var insertedFirstCheckpoint = false

                if (streamingMessageId == null) {
                    streamingMessageId = repository.saveAssistantMessage(
                        conversationId = conversationId,
                        content = checkNotNull(contentSnapshot),
                        isPartial = true,
                        originalQuestion = lastQuestion,
                        clientRequestId = checkNotNull(currentClientRequestId)
                    )
                    sseUpdateCadence.markCheckpoint(nowMs)
                    insertedFirstCheckpoint = true
                }

                if (updateDecision.publishUi || insertedFirstCheckpoint) {
                    publishStreamingSnapshot(checkNotNull(contentSnapshot))
                }

                if (!insertedFirstCheckpoint && updateDecision.checkpoint) {
                    // Persist at a time-based cadence independent of backend chunk size/rate.
                    // The terminal path always flushes the final content.
                    repository.updateAssistantMessage(
                        checkNotNull(streamingMessageId),
                        checkNotNull(contentSnapshot),
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
                if (
                    event.conversationId != conversationId ||
                    event.userMessageId != currentUserMessageId
                ) {
                    persistTerminalFailure(
                        message = "Stream completion metadata does not match this request",
                        retryable = true
                    )
                    return
                }
                val finalAnswer = accumulatedAnswer.toString()
                publishStreamingSnapshot(finalAnswer)
                val localMessageId = streamingMessageId
                val terminalMessageId = if (localMessageId != null) {
                    if (event.status == "completed") {
                        repository.finalizeAssistantMessage(localMessageId, finalAnswer, currentRoute)
                    } else {
                        repository.updateAssistantMessage(
                            localMessageId,
                            finalAnswer,
                            isPartial = event.status == "partial"
                        )
                    }
                    repository.adoptServerMessageIdentity(localMessageId, event)
                    event.assistantMessageId
                } else {
                    repository.saveTerminalAssistantMessage(
                        event = event,
                        question = lastQuestion,
                        clientRequestId = currentClientRequestId.orEmpty()
                    )
                }
                streamingMessageId = terminalMessageId
                if (event.status == "completed") {
                    responseNotificationManager.notifyResponseCompleted(
                        conversationId = conversationId,
                        messageId = terminalMessageId,
                        answer = finalAnswer
                    )
                    streamingMessageId = null
                    _streamState.value = StreamState.Completed(
                        fullAnswer = finalAnswer,
                        route = currentRoute,
                        toolStats = currentToolStats
                    )
                } else {
                    _streamState.value = StreamState.Error(
                        message = if (event.status == "partial") {
                            "Response generation was interrupted"
                        } else {
                            "Response generation failed"
                        },
                        partial = event.status == "partial",
                        retryable = true,
                        partialAnswer = finalAnswer,
                        lastQuestion = lastQuestion
                    )
                }
                // Any tool still CALLING when the stream ends did not finish.
                if (_toolCalls.value.any { it.status == ToolCallStatus.CALLING }) {
                    _toolCalls.value = _toolCalls.value.map {
                        if (it.status == ToolCallStatus.CALLING) it.copy(status = ToolCallStatus.ERROR)
                        else it
                    }
                }
                _activeRoute.value = currentRoute
                _traceLines.value = emptyList()
                startMessageObservation()
                // Show completion indicator briefly
                if (event.status == "completed") {
                    _justCompleted.value = true
                    kotlinx.coroutines.delay(2500)
                    _justCompleted.value = false
                }
            }

            is SseEvent.Error -> {
                persistTerminalFailure(event.message, event.retryable)
            }
        }
    }

    private suspend fun handleStreamException(e: Throwable) {
        persistTerminalFailure(
            message = e.message ?: if (accumulatedAnswer.isNotEmpty()) "Connection lost" else "Send failed",
            retryable = true
        )
    }

    /**
     * Persist every untrusted/incomplete terminal path with one rule: generated
     * content is partial and resumable; an empty answer is failed. This handles
     * malformed or mismatched done metadata, SSE error events, and transport loss.
     */
    private suspend fun persistTerminalFailure(message: String, retryable: Boolean) {
        val failure = ChatTerminalFailure.from(accumulatedAnswer.toString())
        publishStreamingSnapshot(failure.content)
        val terminalMessageId = streamingMessageId ?: repository.saveAssistantMessage(
            conversationId = conversationId,
            content = failure.content,
            isPartial = failure.isPartial,
            originalQuestion = lastQuestion,
            errorMessage = message,
            clientRequestId = checkNotNull(currentClientRequestId),
            status = failure.status
        )
        streamingMessageId = terminalMessageId
        repository.markAssistantTerminal(
            messageId = terminalMessageId,
            content = failure.content,
            status = failure.status,
            errorMessage = message
        )
        _streamState.value = StreamState.Error(
            message = message,
            partial = failure.isPartial,
            retryable = retryable,
            partialAnswer = failure.content,
            lastQuestion = lastQuestion
        )
        startMessageObservation()
    }

    // ============================================================
    // Helpers
    // ============================================================

    private suspend fun deleteMessage(id: String) {
        repository.deleteMessage(id)
    }

    /**
     * Rebuild in-memory resume state from the latest persisted partial/failed turn.
     *
     * Called on every Room emission but guarded by [StreamState.Idle], so it
     * effectively runs once — right after the ViewModel is (re)created. Covers
     * the force-close-mid-stream case: process death wipes [_streamState],
     * [accumulatedAnswer] and [streamingMessageId], yet the partial reply was
     * already checkpointed to Room during streaming. We restore all three so that:
     *   - the "继续生成 / 重新生成" banner shows again (Error + retryable), and
     *   - [continueGeneration] appends the backend's delta onto [accumulatedAnswer]
     *     (which now holds the partial) → finalizes partial + continuation into
     *     the SAME row, instead of overwriting it with the tail only, and
     *   - [regenerate] deletes the right partial row via [streamingMessageId].
     *
     * A partial response must contain resumable text. A failed response may be
     * empty and is restored so the original request can still be retried.
     */
    private fun restorePartialStateIfAny(entities: List<MessageEntity>) {
        if (_streamState.value !is StreamState.Idle) return
        val recovered = ChatRecovery.from(entities) ?: return
        val last = recovered.assistant
        accumulatedAnswer.clear()
        accumulatedAnswer.append(last.content)
        streamingMessageId = last.id
        currentClientRequestId = last.clientRequestId
        currentUserMessageId = recovered.user.id
        currentClientCreatedAt = recovered.user.clientCreatedAt
        sseUpdateCadence.reset(android.os.SystemClock.elapsedRealtime())
        val recoveredFromProcessDeath = last.status == "streaming"
        if (recoveredFromProcessDeath) {
            viewModelScope.launch {
                repository.markAssistantTerminal(
                    messageId = last.id,
                    content = last.content,
                    status = "partial",
                    errorMessage = "Generation interrupted when app process stopped"
                )
            }
        }
        _streamState.value = StreamState.Error(
            partial = last.status == "partial" || recoveredFromProcessDeath,
            retryable = true,
            partialAnswer = last.content,
            lastQuestion = last.originalQuestion ?: recovered.user.content,
            message = last.errorMessage
                ?: if (recoveredFromProcessDeath) "Generation was interrupted" else ""
        )
    }

    fun clearError() {
        _streamState.value = StreamState.Idle
    }

    /** Shows the successfully persisted user row without a Room query round-trip. */
    private fun publishUserMessage(messageId: String, content: String, timestamp: Long) {
        val userMessage = MessageUiState(
            id = messageId,
            role = "user",
            content = content,
            timestamp = timestamp,
            status = "completed"
        )
        val current = _messages.value
        val index = current.indexOfFirst { it.id == messageId }
        _messages.value = if (index >= 0) {
            current.toMutableList().also { it[index] = userMessage }
        } else {
            current + userMessage
        }
    }

    /**
     * Publishes the hot streaming row without waiting for a Room invalidation/query round-trip.
     * Room observation is paused until the stream reaches a terminal state.
     */
    private fun publishStreamingSnapshot(content: String) {
        val messageId = streamingMessageId ?: return
        val current = _messages.value
        val index = current.indexOfFirst { it.id == messageId }
        val updated = if (index >= 0) {
            current.toMutableList().also { messages ->
                messages[index] = messages[index].copy(
                    content = content,
                    isPartial = true,
                    status = "streaming"
                )
            }
        } else {
            current + MessageUiState(
                id = messageId,
                role = "assistant",
                content = content,
                timestamp = System.currentTimeMillis(),
                isPartial = true,
                originalQuestion = lastQuestion,
                status = "streaming"
            )
        }
        _messages.value = updated
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
                // Format: "[tool] 开始调用 #N <name> args=<json>" — the "#N"
                // call-number sits BETWEEN "开始调用" and the name, so we must
                // skip it to reach the name. Otherwise the start entry is stored
                // under "#N" and never matches the "调用完成 <name>" completion
                // line → the card stays CALLING and gets flipped to ERROR at Done.
                val afterStart = line.substringAfter("开始调用", "").trim()
                val nameAndArgs = if (afterStart.startsWith("#")) {
                    afterStart.substringAfter(" ", "").trim()
                } else {
                    afterStart
                }
                val name = nameAndArgs.substringBefore(" args=").substringBefore(" ").trim()
                    .ifEmpty { "tool" }
                val args = line.substringAfter("args=", "").trim()
                // Unique key: index + name + current count avoids collisions when
                // the same tool is called repeatedly in one stream.
                val key = "tc_${callNo}_${name}_${_toolCalls.value.size}"
                toolCallStartNanos[key] = System.nanoTime()
                _toolCalls.value += ToolCallUi(
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
                val parsedFacts = SessionFactsJson.parse(data.facts)
                _sessionContext.value = SessionContextUi(
                    summary = data.summary,
                    factsRaw = data.facts,
                    facts = parsedFacts.orEmpty(),
                    factsJsonValid = parsedFacts != null
                )
            }.onFailure { e ->
                _sessionContext.value = SessionContextUi(
                    summary = "",
                    factsRaw = "",
                    facts = emptyList(),
                    factsJsonValid = true,
                    errorMessage = e.message ?: "Session context load failed"
                )
            }
        }
    }

    fun updateSessionSummary(newSummary: String) {
        val current = _sessionContext.value ?: return
        _sessionContext.value = current.copy(isSaving = true, errorMessage = null)
        viewModelScope.launch {
            val result = repository.updateSessionSummary(conversationId, newSummary)
            result.onSuccess {
                _sessionContext.value = _sessionContext.value?.copy(
                    summary = newSummary,
                    isSaving = false,
                    errorMessage = null
                )
            }.onFailure { e ->
                _sessionContext.value = _sessionContext.value?.copy(
                    isSaving = false,
                    errorMessage = e.message ?: "Session summary update failed"
                )
            }
        }
    }

    fun deleteSessionFact(key: String) {
        val current = _sessionContext.value ?: return
        val updatedFactsRaw = SessionFactsJson.remove(current.factsRaw, key)
        if (updatedFactsRaw == null) {
            _sessionContext.value = current.copy(errorMessage = "Fact JSON is invalid or the key no longer exists")
            return
        }

        _sessionContext.value = current.copy(isSaving = true, errorMessage = null)
        viewModelScope.launch {
            val result = repository.updateSessionFacts(conversationId, updatedFactsRaw)
            result.onSuccess {
                _sessionContext.value = _sessionContext.value?.copy(
                    factsRaw = updatedFactsRaw,
                    facts = SessionFactsJson.parse(updatedFactsRaw).orEmpty(),
                    factsJsonValid = true,
                    isSaving = false,
                    errorMessage = null
                )
            }.onFailure { e ->
                _sessionContext.value = _sessionContext.value?.copy(
                    isSaving = false,
                    errorMessage = e.message ?: "Session fact deletion failed"
                )
            }
        }
    }

    fun clearSessionContextError() {
        _sessionContext.value = _sessionContext.value?.copy(errorMessage = null)
    }

    fun dismissSessionContext() {
        _sessionContext.value = null
    }

    private fun deriveTitle(question: String): String {
        return if (question.length <= 30) question else question.take(30) + "..."
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
        errorMessage = errorMessage,
        status = status
    )
}

// ============================================================
// UI state models
// ============================================================

@androidx.compose.runtime.Immutable
data class SessionContextUi(
    val summary: String,
    val factsRaw: String,
    val facts: List<SessionFactUi>,
    val factsJsonValid: Boolean,
    val isSaving: Boolean = false,
    val errorMessage: String? = null
)

@androidx.compose.runtime.Immutable
data class MessageUiState(
    val id: String,
    val role: String,
    val content: String,
    val timestamp: Long,
    val isPartial: Boolean = false,
    val route: String? = null,
    val originalQuestion: String? = null,
    val errorMessage: String? = null,
    val status: String = "completed"
)

sealed class UploadState {
    data object Idle : UploadState()
    data object Uploading : UploadState()
    data class Success(val data: UploadData) : UploadState()
    data class Error(val message: String) : UploadState()
}
