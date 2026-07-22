package com.labsage.app.data.repository

import android.net.Uri
import com.labsage.app.LabSageApplication
import com.labsage.app.data.auth.AuthenticationState
import com.labsage.app.data.local.AppDatabase
import com.labsage.app.data.local.dao.ConversationDao
import com.labsage.app.data.local.dao.MessageDao
import com.labsage.app.data.local.entity.ConversationEntity
import com.labsage.app.data.local.entity.MessageEntity
import com.labsage.app.data.remote.*
import com.labsage.app.data.sync.HistoryRemoteSource
import com.labsage.app.data.sync.HistorySyncCoordinator
import com.labsage.app.data.sync.RoomHistorySyncStore
import kotlinx.coroutines.flow.Flow
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.util.UUID

/**
 * Single source of truth for all data operations.
 *
 * Orchestrates between:
 *   - Remote: LabSageApi (Retrofit, non-streaming) + SseDataSource (OkHttp SSE, streaming)
 *   - Local: ConversationDao + MessageDao (Room)
 *
 * The ViewModel talks only to the repository — never to API/DAO directly.
 *
 * The remote layer is accessed through [app] so that a base-URL change made in
 * Settings takes effect for new requests without rebuilding the repository.
 */
class ChatRepository(
    private val app: LabSageApplication,
    database: AppDatabase,
    private val conversationDao: ConversationDao,
    private val messageDao: MessageDao
) {
    private val historySyncStore = RoomHistorySyncStore(database)
    private val historySyncCoordinator = HistorySyncCoordinator(
        remote = object : HistoryRemoteSource {
            override suspend fun getConversations(): List<ConversationDto> {
                val response = app.api.getConversations()
                return response.data?.items ?: error(response.message)
            }

            override suspend fun getMessages(conversationId: String): List<MessageDto> {
                val response = app.api.getMessages(conversationId)
                return response.data?.items ?: error(response.message)
            }
        },
        local = historySyncStore
    )
    // ============================================================
    // Local queries (observable Room Flows)
    // ============================================================

    fun getAllConversations(): Flow<List<ConversationEntity>> =
        conversationDao.getAllConversations(currentUserId())

    /** Title-substring search across conversations (pinned-first ordering). */
    fun searchConversations(query: String): Flow<List<ConversationEntity>> =
        conversationDao.searchConversations(currentUserId(), query)

    fun getMessages(conversationId: String): Flow<List<MessageEntity>> =
        messageDao.getMessagesByConversation(currentUserId(), conversationId)

    suspend fun syncConversations(): Result<Unit> = runCatching {
        historySyncCoordinator.syncConversations(currentUserId())
    }

    suspend fun syncMessages(conversationId: String): Result<Unit> = runCatching {
        historySyncCoordinator.syncMessages(currentUserId(), conversationId)
    }

    // ============================================================
    // Chat operations
    // ============================================================

    /** Non-streaming (quick mode) chat */
    suspend fun sendQuickMessage(sessionId: String, question: String): Result<ChatData> {
        val clientMessageId = UUID.randomUUID().toString()
        val clientRequestId = UUID.randomUUID().toString()
        return try {
            val response = app.api.chat(
                ChatRequest(
                    id = sessionId,
                    question = question,
                    clientMessageId = clientMessageId,
                    clientRequestId = clientRequestId,
                    clientCreatedAt = System.currentTimeMillis()
                )
            )
            if (response.data != null) {
                Result.success(response.data)
            } else {
                Result.failure(Exception("Empty response: ${response.message}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** SSE streaming chat — returns a Flow of parsed events */
    fun sendStreamMessage(
        sessionId: String,
        question: String,
        clientMessageId: String,
        clientRequestId: String,
        clientCreatedAt: Long
    ): Flow<SseEvent> {
        return app.sseDataSource.streamChat(
            ChatStreamRequest(
                id = sessionId,
                question = question,
                clientMessageId = clientMessageId,
                clientRequestId = clientRequestId,
                clientCreatedAt = clientCreatedAt
            )
        )
    }

    /** Resume an interrupted SSE stream via Continue + PartialAnswer */
    fun continueStreamMessage(
        sessionId: String,
        question: String,
        partialAnswer: String,
        clientMessageId: String,
        clientRequestId: String,
        clientCreatedAt: Long
    ): Flow<SseEvent> {
        return app.sseDataSource.streamChat(
            ChatStreamRequest(
                id = sessionId,
                question = question,
                clientMessageId = clientMessageId,
                clientRequestId = clientRequestId,
                clientCreatedAt = clientCreatedAt,
                continueFlag = true,
                partialAnswer = partialAnswer
            )
        )
    }

    // ============================================================
    // File upload
    // ============================================================

    /**
     * Upload a file picked from a content URI.
     *
     * Uses the Application context ([app]) for the ContentResolver and cache dir
     * rather than taking one as a parameter. The repository outlives any Activity
     * (it is a lazy singleton on the Application), so a caller-supplied Activity
     * context captured by this suspending call would leak across a configuration
     * change while the upload is in flight (viewModelScope survives rotation).
     */
    suspend fun uploadFile(uri: Uri): Result<UploadData> {
        return try {
            val contentResolver = app.contentResolver

            // Resolve file name from content URI
            val cursor = contentResolver.query(uri, null, null, null, null)
            val fileName = cursor?.use {
                if (it.moveToFirst()) {
                    val nameIndex = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (nameIndex >= 0) it.getString(nameIndex) else "uploaded_file"
                } else "uploaded_file"
            } ?: "uploaded_file"

            // Copy content to a temp file (needed for OkHttp request body)
            val tempFile = File(app.cacheDir, "upload_$fileName")
            contentResolver.openInputStream(uri)?.use { input ->
                tempFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            } ?: return Result.failure(Exception("Cannot read file"))

            // Build multipart request
            val requestBody = tempFile
                .readBytes()
                .toRequestBody("application/octet-stream".toMediaType())
            val part = MultipartBody.Part.createFormData("file", fileName, requestBody)

            val response = app.api.uploadFile(part)
            // Cleanup temp file
            tempFile.delete()

            if (response.data != null) {
                Result.success(response.data)
            } else {
                Result.failure(Exception("Upload failed: ${response.message}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ============================================================
    // Persistence helpers
    // ============================================================

    suspend fun saveUserMessage(
        conversationId: String,
        content: String,
        messageId: String = UUID.randomUUID().toString(),
        clientRequestId: String = UUID.randomUUID().toString(),
        clientCreatedAt: Long = System.currentTimeMillis()
    ): String {
        messageDao.insertOwned(
            currentUserId(),
            MessageEntity(
                id = messageId,
                conversationId = conversationId,
                role = "user",
                content = content,
                status = "completed",
                clientRequestId = clientRequestId,
                clientCreatedAt = clientCreatedAt
            )
        )
        return messageId
    }

    /**
     * Save an assistant message placeholder.
     * Returns the auto-generated message ID for later updates during streaming.
     */
    suspend fun saveAssistantMessage(
        conversationId: String,
        content: String,
        route: String? = null,
        isPartial: Boolean = false,
        originalQuestion: String? = null,
        errorMessage: String? = null,
        clientRequestId: String,
        status: String = if (isPartial) "streaming" else "completed"
    ): String {
        val messageId = UUID.randomUUID().toString()
        return messageDao.insertOrReuseAssistantOwned(
            currentUserId(),
            MessageEntity(
                id = messageId,
                conversationId = conversationId,
                role = "assistant",
                content = content,
                route = route,
                isPartial = isPartial,
                originalQuestion = originalQuestion,
                errorMessage = errorMessage,
                status = status,
                clientRequestId = clientRequestId
            )
        )
    }

    /** Update streaming content in-place (called on each SSE 'message' chunk) */
    suspend fun updateAssistantMessage(messageId: String, content: String, isPartial: Boolean) {
        messageDao.updateContent(currentUserId(), messageId, content, isPartial)
    }

    suspend fun markAssistantTerminal(
        messageId: String,
        content: String,
        status: String,
        errorMessage: String?
    ) {
        messageDao.markTerminal(
            userId = currentUserId(),
            messageId = messageId,
            content = content,
            status = status,
            isPartial = status == "partial",
            errorMessage = errorMessage
        )
    }

    suspend fun markAssistantStreaming(messageId: String) {
        messageDao.markStreaming(currentUserId(), messageId)
    }

    /** Mark a streamed message as complete */
    suspend fun finalizeAssistantMessage(messageId: String, content: String, route: String?) {
        messageDao.finalizeMessage(currentUserId(), messageId, content, route)
    }

    suspend fun adoptServerMessageIdentity(localId: String, event: SseEvent.Done) {
        messageDao.adoptServerIdentity(
            userId = currentUserId(),
            localId = localId,
            serverId = event.assistantMessageId,
            status = event.status,
            syncVersion = event.syncVersion,
            serverUpdatedAt = event.updatedAt
        )
    }

    suspend fun saveTerminalAssistantMessage(
        event: SseEvent.Done,
        question: String,
        clientRequestId: String
    ): String {
        return messageDao.mergeTerminalOwned(
            currentUserId(),
            MessageEntity(
                id = event.assistantMessageId,
                conversationId = event.conversationId,
                role = "assistant",
                content = "",
                status = event.status,
                isPartial = event.status == "partial",
                originalQuestion = question,
                clientRequestId = clientRequestId,
                syncVersion = event.syncVersion,
                serverUpdatedAt = event.updatedAt
            )
        )
    }

    suspend fun upsertConversation(conversationId: String, title: String) {
        val userId = currentUserId()
        val existing = conversationDao.getById(userId, conversationId)
        if (existing != null) {
            // Use UPDATE to avoid FK CASCADE from REPLACE
            conversationDao.updateMeta(userId, conversationId, title, System.currentTimeMillis())
        } else {
            // Fresh insert — IGNORE conflict (safe, no cascade risk)
            val inserted = conversationDao.insert(
                ConversationEntity(
                    id = conversationId,
                    title = title,
                    createdAt = System.currentTimeMillis(),
                    updatedAt = System.currentTimeMillis(),
                    ownerUserId = userId
                )
            )
            check(inserted != -1L || conversationDao.getById(userId, conversationId) != null) {
                "Conversation ID belongs to a different local account"
            }
        }
    }

    suspend fun deleteConversation(conversationId: String) {
        conversationDao.deleteById(currentUserId(), conversationId)
    }

    /** Pin or unpin a conversation (pinned ones sort to the top). */
    suspend fun setConversationPinned(conversationId: String, pinned: Boolean) {
        conversationDao.setPinned(currentUserId(), conversationId, pinned)
    }

    /** Rename a conversation without changing its updated_at (no reordering). */
    suspend fun renameConversation(conversationId: String, newTitle: String) {
        conversationDao.updateTitle(currentUserId(), conversationId, newTitle.trim())
    }

    suspend fun deleteMessage(messageId: String) {
        messageDao.deleteById(currentUserId(), messageId)
    }

    // ============================================================
    // Session context (summary + facts)
    // ============================================================

    suspend fun getSessionContext(sessionId: String): Result<SessionContextData> {
        return try {
            val response = app.api.getSessionContext(sessionId)
            if (response.data != null) Result.success(response.data)
            else Result.failure(Exception("Empty response"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun updateSessionSummary(sessionId: String, summary: String): Result<Boolean> {
        return updateSessionContext(
            UpdateSessionContextRequest(id = sessionId, summary = summary)
        )
    }

    suspend fun updateSessionFacts(sessionId: String, facts: String): Result<Boolean> {
        return updateSessionContext(
            UpdateSessionContextRequest(id = sessionId, facts = facts)
        )
    }

    private suspend fun updateSessionContext(
        request: UpdateSessionContextRequest
    ): Result<Boolean> {
        return try {
            val response = app.api.updateSessionContext(request)
            if (response.data?.ok == true) {
                Result.success(true)
            } else {
                Result.failure(Exception(response.message.ifBlank { "Session context update failed" }))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun currentUserId(): String {
        return (app.authRepository.state.value as? AuthenticationState.Authenticated)?.user?.id
            ?: error("History access requires an authenticated user")
    }
}
