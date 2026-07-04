package com.labsage.app.data.repository

import android.content.Context
import android.net.Uri
import com.labsage.app.LabSageApplication
import com.labsage.app.data.local.dao.ConversationDao
import com.labsage.app.data.local.dao.MessageDao
import com.labsage.app.data.local.entity.ConversationEntity
import com.labsage.app.data.local.entity.MessageEntity
import com.labsage.app.data.remote.*
import kotlinx.coroutines.flow.Flow
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File

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
    private val conversationDao: ConversationDao,
    private val messageDao: MessageDao
) {
    // ============================================================
    // Local queries (observable Room Flows)
    // ============================================================

    fun getAllConversations(): Flow<List<ConversationEntity>> =
        conversationDao.getAllConversations()

    /** Title-substring search across conversations (pinned-first ordering). */
    fun searchConversations(query: String): Flow<List<ConversationEntity>> =
        conversationDao.searchConversations(query)

    fun getMessages(conversationId: String): Flow<List<MessageEntity>> =
        messageDao.getMessagesByConversation(conversationId)

    // ============================================================
    // Chat operations
    // ============================================================

    /** Non-streaming (quick mode) chat */
    suspend fun sendQuickMessage(sessionId: String, question: String): Result<ChatData> {
        return try {
            val response = app.api.chat(ChatRequest(sessionId, question))
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
    fun sendStreamMessage(sessionId: String, question: String): Flow<SseEvent> {
        return app.sseDataSource.streamChat(
            ChatStreamRequest(id = sessionId, question = question)
        )
    }

    /** Resume an interrupted SSE stream via Continue + PartialAnswer */
    fun continueStreamMessage(
        sessionId: String,
        question: String,
        partialAnswer: String
    ): Flow<SseEvent> {
        return app.sseDataSource.streamChat(
            ChatStreamRequest(
                id = sessionId,
                question = question,
                continueFlag = true,
                partialAnswer = partialAnswer
            )
        )
    }

    // ============================================================
    // File upload
    // ============================================================

    suspend fun uploadFile(uri: Uri, context: Context): Result<UploadData> {
        return try {
            val contentResolver = context.contentResolver

            // Resolve file name from content URI
            val cursor = contentResolver.query(uri, null, null, null, null)
            val fileName = cursor?.use {
                if (it.moveToFirst()) {
                    val nameIndex = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (nameIndex >= 0) it.getString(nameIndex) else "uploaded_file"
                } else "uploaded_file"
            } ?: "uploaded_file"

            // Copy content to a temp file (needed for OkHttp request body)
            val tempFile = File(context.cacheDir, "upload_$fileName")
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

    suspend fun saveUserMessage(conversationId: String, content: String) {
        messageDao.insert(
            MessageEntity(
                conversationId = conversationId,
                role = "user",
                content = content
            )
        )
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
        errorMessage: String? = null
    ): Long {
        return messageDao.insert(
            MessageEntity(
                conversationId = conversationId,
                role = "assistant",
                content = content,
                route = route,
                isPartial = isPartial,
                originalQuestion = originalQuestion,
                errorMessage = errorMessage
            )
        )
    }

    /** Update streaming content in-place (called on each SSE 'message' chunk) */
    suspend fun updateAssistantMessage(messageId: Long, content: String, isPartial: Boolean) {
        messageDao.updateContent(messageId, content, isPartial)
    }

    /** Mark a streamed message as complete */
    suspend fun finalizeAssistantMessage(messageId: Long, content: String, route: String?) {
        messageDao.finalizeMessage(messageId, content, route)
    }

    suspend fun upsertConversation(conversationId: String, title: String) {
        val existing = conversationDao.getById(conversationId)
        if (existing != null) {
            // Use UPDATE to avoid FK CASCADE from REPLACE
            conversationDao.updateMeta(conversationId, title, System.currentTimeMillis())
        } else {
            // Fresh insert — IGNORE conflict (safe, no cascade risk)
            conversationDao.insert(
                ConversationEntity(
                    id = conversationId,
                    title = title,
                    createdAt = System.currentTimeMillis(),
                    updatedAt = System.currentTimeMillis()
                )
            )
        }
    }

    suspend fun deleteConversation(conversationId: String) {
        conversationDao.deleteById(conversationId)
    }

    /** Pin or unpin a conversation (pinned ones sort to the top). */
    suspend fun setConversationPinned(conversationId: String, pinned: Boolean) {
        conversationDao.setPinned(conversationId, pinned)
    }

    /** Rename a conversation without changing its updated_at (no reordering). */
    suspend fun renameConversation(conversationId: String, newTitle: String) {
        conversationDao.updateTitle(conversationId, newTitle.trim())
    }

    suspend fun deleteMessage(messageId: Long) {
        messageDao.deleteById(messageId)
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
        return try {
            val request = UpdateSessionContextRequest(id = sessionId, summary = summary)
            val response = app.api.updateSessionContext(request)
            Result.success(response.data?.ok == true)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
