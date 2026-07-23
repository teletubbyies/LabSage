package com.labsage.app.data.repository

import android.net.Uri
import android.provider.OpenableColumns
import com.labsage.app.LabSageApplication
import com.labsage.app.data.auth.AuthenticationState
import com.labsage.app.data.local.AppDatabase
import com.labsage.app.data.local.dao.ConversationDao
import com.labsage.app.data.local.dao.MessageDao
import com.labsage.app.data.local.entity.ConversationEntity
import com.labsage.app.data.local.entity.MessageEntity
import com.labsage.app.data.remote.*
import com.labsage.app.data.sync.HistoryRemoteSource
import com.labsage.app.data.sync.HistoryPage
import com.labsage.app.data.sync.HistorySyncCoordinator
import com.labsage.app.data.sync.DataStoreHistoryCursorStore
import com.labsage.app.data.sync.DataStoreLegacyImportCompletionStore
import com.labsage.app.data.sync.LegacyHistoryImportCoordinator
import com.labsage.app.data.sync.LegacyImportBinding
import com.labsage.app.data.sync.LegacyImportLocalSource
import com.labsage.app.data.sync.LegacyImportRemoteSource
import com.labsage.app.data.sync.RoomHistorySyncStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okio.source
import retrofit2.HttpException
import java.io.File
import java.io.FileNotFoundException
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
    private companion object {
        const val MAX_MARKDOWN_BYTES = 10L * 1024 * 1024
        const val MARKDOWN_MEDIA_TYPE = "text/markdown; charset=utf-8"
    }

    private val historySyncStore = RoomHistorySyncStore(database)
    private val historyCursorStore = DataStoreHistoryCursorStore(app)
    private val legacyImportCoordinator = LegacyHistoryImportCoordinator(
        remote = LegacyImportRemoteSource { binding, request ->
            check(isLegacyImportBindingCurrent(binding))
            var session = app.authRepository.sessionForBoundOperation(
                binding.userId, binding.serverKey
            )
                ?: error("Authentication changed during legacy import")
            val response = try {
                app.legacyImportApiFor(session).importLegacyHistory(request)
            } catch (error: HttpException) {
                if (error.code() != 401) throw error
                session = app.authRepository.sessionForBoundOperation(
                    binding.userId, binding.serverKey, forceRefresh = true
                ) ?: error("Authentication changed during legacy import")
                app.legacyImportApiFor(session).importLegacyHistory(request)
            }
            response.data ?: error(response.message)
        },
        local = LegacyImportLocalSource { binding ->
            historySyncStore.legacyImportSnapshot(binding.userId, binding.serverKey)
        },
        completionStore = DataStoreLegacyImportCompletionStore(app),
        isBindingCurrent = ::isLegacyImportBindingCurrent
    )
    private val historySyncCoordinator = HistorySyncCoordinator(
        remote = object : HistoryRemoteSource {
            override suspend fun getConversations(
                userId: String,
                serverKey: String,
                updatedAfter: Long?
            ): HistoryPage<ConversationDto> {
                val response = withBoundHistoryApi(userId, serverKey) {
                    getConversations(updatedAfter)
                }
                val data = response.data ?: error(response.message)
                return HistoryPage(data.items, data.nextCursor, data.serverTime)
            }

            override suspend fun getMessages(
                userId: String,
                serverKey: String,
                conversationId: String,
                updatedAfter: Long?
            ): HistoryPage<MessageDto> {
                val response = withBoundHistoryApi(userId, serverKey) {
                    getMessages(conversationId, updatedAfter)
                }
                val data = response.data ?: error(response.message)
                return HistoryPage(data.items, data.nextCursor, data.serverTime)
            }
        },
        local = historySyncStore,
        cursors = historyCursorStore,
        isBindingCurrent = { userId, serverKey ->
            isLegacyImportBindingCurrent(LegacyImportBinding(userId, serverKey))
        },
        commitWithStableBinding = { userId, serverKey, block ->
            app.authRepository.withStableSessionBinding(userId, serverKey, block)
        }
    )
    // ============================================================
    // Local queries (observable Room Flows)
    // ============================================================

    fun getAllConversations(): Flow<List<ConversationEntity>> {
        val binding = currentLegacyImportBinding()
        return conversationDao.getAllConversations(binding.userId, binding.serverKey)
    }

    /** Title-substring search across conversations (pinned-first ordering). */
    fun searchConversations(query: String): Flow<List<ConversationEntity>> {
        val binding = currentLegacyImportBinding()
        return conversationDao.searchConversations(binding.userId, query, binding.serverKey)
    }

    fun getMessages(conversationId: String): Flow<List<MessageEntity>> {
        val binding = currentLegacyImportBinding()
        return messageDao.getMessagesByConversation(
            binding.userId,
            conversationId,
            binding.serverKey
        )
    }

    suspend fun syncConversations(): Result<Unit> = runCatching {
        val binding = currentLegacyImportBinding()
        legacyImportCoordinator.ensureImported(binding)
        historySyncCoordinator.syncConversations(binding.userId, binding.serverKey)
    }

    suspend fun syncMessages(conversationId: String): Result<Unit> = runCatching {
        val binding = currentLegacyImportBinding()
        legacyImportCoordinator.ensureImported(binding)
        historySyncCoordinator.syncMessages(binding.userId, binding.serverKey, conversationId)
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
     * Known-size content streams directly from ContentResolver into OkHttp. Providers
     * that do not expose a size fall back to a bounded temporary file, still without
     * allocating a byte array proportional to the document.
     */
    suspend fun uploadFile(uri: Uri): Result<UploadData> {
        var stagedFile: File? = null
        return try {
            val prepared = prepareMarkdownUpload(uri)
            stagedFile = prepared.stagedFile
            val part = MultipartBody.Part.createFormData(
                "file",
                prepared.fileName,
                prepared.requestBody
            )
            val response = app.api.uploadFile(part)
            if (response.data != null) {
                Result.success(response.data)
            } else {
                Result.failure(Exception("Upload failed: ${response.message}"))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            withContext(NonCancellable + Dispatchers.IO) {
                stagedFile?.delete()
            }
        }
    }

    private suspend fun prepareMarkdownUpload(uri: Uri): PreparedUpload {
        var stagedFile: File? = null
        try {
            return withContext(Dispatchers.IO) {
                val metadata = resolveUploadMetadata(uri)
                validateMarkdownMetadata(metadata)
                val mediaType = MARKDOWN_MEDIA_TYPE.toMediaType()

                if (metadata.size != null) {
                    val body = StreamingRequestBody(metadata.size, mediaType) {
                        val input = app.contentResolver.openInputStream(uri)
                            ?: throw FileNotFoundException("Cannot read selected Markdown file")
                        input.source()
                    }
                    PreparedUpload(metadata.fileName, body)
                } else {
                    val suffix = if (metadata.fileName.endsWith(".markdown", ignoreCase = true)) {
                        ".markdown"
                    } else {
                        ".md"
                    }
                    val tempFile = File.createTempFile("upload_", suffix, app.cacheDir)
                    stagedFile = tempFile
                    val copied = app.contentResolver.openInputStream(uri)?.use { input ->
                        tempFile.outputStream().use { output ->
                            copyWithByteLimit(input, output, MAX_MARKDOWN_BYTES)
                        }
                    } ?: throw FileNotFoundException("Cannot read selected Markdown file")
                    require(copied > 0) { "Markdown file is empty" }
                    PreparedUpload(
                        fileName = metadata.fileName,
                        requestBody = tempFile.asRequestBody(mediaType),
                        stagedFile = tempFile
                    )
                }
            }
        } catch (error: Throwable) {
            withContext(NonCancellable + Dispatchers.IO) {
                stagedFile?.delete()
            }
            throw error
        }
    }

    private fun resolveUploadMetadata(uri: Uri): UploadMetadata {
        var fileName: String? = null
        var size: Long? = null
        app.contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
            null,
            null,
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex >= 0 && !cursor.isNull(nameIndex)) {
                    fileName = cursor.getString(nameIndex)
                }
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) {
                    size = cursor.getLong(sizeIndex)
                }
            }
        }
        return UploadMetadata(
            fileName = fileName?.trim().orEmpty().ifEmpty { "notes.md" },
            size = normalizedUploadSize(size)
        )
    }

    private fun validateMarkdownMetadata(metadata: UploadMetadata) {
        val validExtension = metadata.fileName.endsWith(".md", ignoreCase = true) ||
            metadata.fileName.endsWith(".markdown", ignoreCase = true)
        require(validExtension) { "Only Markdown (.md or .markdown) files are supported" }
        metadata.size?.let { size ->
            require(size > 0) { "Markdown file is empty" }
            if (size > MAX_MARKDOWN_BYTES) {
                throw UploadTooLargeException(MAX_MARKDOWN_BYTES)
            }
        }
    }

    private data class UploadMetadata(
        val fileName: String,
        val size: Long?
    )

    private data class PreparedUpload(
        val fileName: String,
        val requestBody: RequestBody,
        val stagedFile: File? = null
    )

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
        val binding = currentLegacyImportBinding()
        val userId = binding.userId
        val existing = conversationDao.getById(userId, conversationId, binding.serverKey)
        if (existing != null) {
            // Use UPDATE to avoid FK CASCADE from REPLACE
            conversationDao.updateMeta(
                userId,
                conversationId,
                title,
                System.currentTimeMillis(),
                binding.serverKey
            )
        } else {
            // Fresh insert — IGNORE conflict (safe, no cascade risk)
            val inserted = conversationDao.insert(
                ConversationEntity(
                    id = conversationId,
                    title = title,
                    createdAt = System.currentTimeMillis(),
                    updatedAt = System.currentTimeMillis(),
                    ownerUserId = userId,
                    ownerServerKey = binding.serverKey
                )
            )
            check(inserted != -1L || conversationDao.getById(
                userId,
                conversationId,
                binding.serverKey
            ) != null) {
                "Conversation ID belongs to a different local account or backend"
            }
        }
    }

    suspend fun deleteConversation(conversationId: String): Result<Unit> = runCatching {
        val binding = currentLegacyImportBinding()
        val local = loadMutableConversation(binding, conversationId)
        val response = try {
            withBoundHistoryApi(binding.userId, binding.serverKey) {
                deleteConversation(conversationId, local.version)
            }
        } catch (error: HttpException) {
            refreshAfterConversationConflict(binding, error)
            throw error
        }
        applyConversationMutation(binding, response.data ?: error(response.message))
    }

    /** Pin or unpin a conversation (pinned ones sort to the top). */
    suspend fun setConversationPinned(
        conversationId: String,
        pinned: Boolean
    ): Result<Unit> = runCatching {
        val binding = currentLegacyImportBinding()
        patchConversation(binding, conversationId, title = null, isPinned = pinned)
    }

    /** Rename a conversation without changing its updated_at (no reordering). */
    suspend fun renameConversation(
        conversationId: String,
        newTitle: String
    ): Result<Unit> = runCatching {
        val binding = currentLegacyImportBinding()
        patchConversation(binding, conversationId, title = newTitle.trim(), isPinned = null)
    }

    private suspend fun patchConversation(
        binding: LegacyImportBinding,
        conversationId: String,
        title: String?,
        isPinned: Boolean?
    ) {
        val local = loadMutableConversation(binding, conversationId)
        val response = try {
            withBoundHistoryApi(binding.userId, binding.serverKey) {
                patchConversation(
                    conversationId,
                    ConversationPatchRequest(title, isPinned, local.version)
                )
            }
        } catch (error: HttpException) {
            refreshAfterConversationConflict(binding, error)
            throw error
        }
        applyConversationMutation(binding, response.data ?: error(response.message))
    }

    private suspend fun applyConversationMutation(
        binding: LegacyImportBinding,
        conversation: ConversationDto
    ) {
        app.authRepository.withStableSessionBinding(binding.userId, binding.serverKey) {
            historySyncStore.mergeConversations(
                binding.userId,
                binding.serverKey,
                listOf(conversation)
            )
        }
    }

    private suspend fun loadMutableConversation(
        binding: LegacyImportBinding,
        conversationId: String
    ): ConversationEntity {
        var local = conversationDao.getById(
            binding.userId,
            conversationId,
            binding.serverKey
        ) ?: error("Conversation is unavailable")
        if (local.version <= 0) {
            historySyncCoordinator.syncConversations(
                binding.userId,
                binding.serverKey,
                forceFull = true
            )
            local = conversationDao.getById(
                binding.userId,
                conversationId,
                binding.serverKey
            ) ?: error("Conversation is unavailable on the server")
        }
        check(local.version > 0) { "Conversation has no server version" }
        return local
    }

    private suspend fun refreshAfterConversationConflict(
        binding: LegacyImportBinding,
        error: HttpException
    ) {
        if (error.code() == 409) {
            historySyncCoordinator.syncConversations(
                binding.userId,
                binding.serverKey,
                forceFull = true
            )
        }
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

    private suspend fun <T> withBoundHistoryApi(
        userId: String,
        serverKey: String,
        request: suspend LabSageApi.() -> T
    ): T {
        var session = app.authRepository.sessionForBoundOperation(userId, serverKey)
            ?: error("Authentication changed during history synchronization")
        return try {
            app.legacyImportApiFor(session).request()
        } catch (error: HttpException) {
            if (error.code() != 401) throw error
            session = app.authRepository.sessionForBoundOperation(
                userId,
                serverKey,
                forceRefresh = true
            ) ?: error("Authentication changed during history synchronization")
            app.legacyImportApiFor(session).request()
        }
    }

    private fun currentUserId(): String {
        return (app.authRepository.state.value as? AuthenticationState.Authenticated)?.user?.id
            ?: error("History access requires an authenticated user")
    }

    private fun currentLegacyImportBinding(): LegacyImportBinding {
        val session = app.authRepository.sessionSnapshot()
            ?: error("Legacy import requires an authenticated server session")
        return LegacyImportBinding(
            userId = session.user.id,
            serverKey = session.serverBaseUrl
                ?: error("Authenticated session has no server binding")
        )
    }

    private fun isLegacyImportBindingCurrent(binding: LegacyImportBinding): Boolean {
        val session = app.authRepository.sessionSnapshot() ?: return false
        return session.user.id == binding.userId &&
            session.serverBaseUrl == binding.serverKey
    }
}
