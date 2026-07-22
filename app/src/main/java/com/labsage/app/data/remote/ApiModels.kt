package com.labsage.app.data.remote

import com.google.gson.annotations.SerializedName

// ============================================================
// Request DTOs
// ============================================================

/**
 * Non-streaming chat request — POST /api/chat
 *
 * Matches the Go struct in api/chat/v1/chat.go:
 *   type ChatReq struct {
 *       Id       string `json:"Id"`
 *       Question string `json:"Question"`
 *   }
 */
data class ChatRequest(
    @SerializedName("Id")
    val id: String,
    @SerializedName("Question")
    val question: String,
    @SerializedName("ClientMessageId")
    val clientMessageId: String,
    @SerializedName("ClientRequestId")
    val clientRequestId: String,
    @SerializedName("ClientCreatedAt")
    val clientCreatedAt: Long
)

/**
 * SSE streaming chat request — POST /api/chat_stream
 *
 * Matches the Go struct in api/chat/v1/chat.go:
 *   type ChatStreamReq struct {
 *       Id            string `json:"Id"`
 *       Question      string `json:"Question"`
 *       Continue      bool   `json:"Continue"`
 *       PartialAnswer string `json:"PartialAnswer"`
 *   }
 */
data class ChatStreamRequest(
    @SerializedName("Id")
    val id: String,
    @SerializedName("Question")
    val question: String,
    @SerializedName("ClientMessageId")
    val clientMessageId: String,
    @SerializedName("ClientRequestId")
    val clientRequestId: String,
    @SerializedName("ClientCreatedAt")
    val clientCreatedAt: Long,
    @SerializedName("Continue")
    val continueFlag: Boolean = false,
    @SerializedName("PartialAnswer")
    val partialAnswer: String = ""
)

// ============================================================
// Response envelope
// ============================================================

/**
 * Unified API response envelope — all non-SSE responses are wrapped in:
 *   { "message": "OK", "data": <actual payload> }
 *
 * Matches GoFrame's ResponseMiddleware pattern.
 */
data class ApiResponse<T>(
    @SerializedName("message")
    val message: String,
    @SerializedName("data")
    val data: T?
)

// ============================================================
// Response data classes
// ============================================================

/**
 * Non-streaming chat response data.
 *   type ChatRes struct {
 *       Answer    string         `json:"answer"`
 *       Route     string         `json:"route"`
 *       Trace     []string       `json:"trace"`
 *       ToolStats map[string]int `json:"toolStats"`
 *   }
 */
data class ChatData(
    @SerializedName("answer")
    val answer: String,
    @SerializedName("route")
    val route: String? = null,
    @SerializedName("trace")
    val trace: List<String>? = null,
    @SerializedName("toolStats")
    val toolStats: Map<String, Int>? = null
)

/**
 * File upload response data.
 *   type FileUploadRes struct {
 *       FileName string `json:"fileName"`
 *       FilePath string `json:"filePath"`
 *       FileSize int64  `json:"fileSize"`
 *   }
 */
data class UploadData(
    @SerializedName("fileName")
    val fileName: String,
    @SerializedName("filePath")
    val filePath: String,
    @SerializedName("fileSize")
    val fileSize: Long
)

// ============================================================
// SSE event payloads (JSON data fields within SSE events)
// ============================================================

/** Payload of "event: connected" */
data class ConnectedPayload(
    @SerializedName("status")
    val status: String,
    @SerializedName("client_id")
    val clientId: String
)

/** Payload of "event: trace_summary" */
data class TraceSummaryPayload(
    @SerializedName("tool_stats")
    val toolStats: Map<String, Int> = emptyMap(),
    @SerializedName("total_tool_calls")
    val totalToolCalls: Int = 0,
    @SerializedName("trace_count")
    val traceCount: Int = 0
)

/** Payload of "event: error" */
data class SseErrorPayload(
    @SerializedName("message")
    val message: String,
    @SerializedName("partial")
    val partial: Boolean = false,
    @SerializedName("retryable")
    val retryable: Boolean = true
)

/** Structured terminal metadata returned by the backend's SSE done event. */
data class SseDonePayload(
    @SerializedName("conversationId") val conversationId: String,
    @SerializedName("userMessageId") val userMessageId: String,
    @SerializedName("assistantMessageId") val assistantMessageId: String,
    @SerializedName("status") val status: String,
    @SerializedName("updatedAt") val updatedAt: Long,
    @SerializedName("syncVersion") val syncVersion: Long
)

data class ConversationListData(
    @SerializedName("items") val items: List<ConversationDto> = emptyList()
)

data class ConversationDto(
    @SerializedName("id") val id: String,
    @SerializedName("title") val title: String,
    @SerializedName("isPinned") val isPinned: Boolean,
    @SerializedName("version") val version: Long,
    @SerializedName("syncVersion") val syncVersion: Long,
    @SerializedName("createdAt") val createdAt: Long,
    @SerializedName("updatedAt") val updatedAt: Long
)

data class MessageListData(
    @SerializedName("items") val items: List<MessageDto> = emptyList()
)

data class MessageDto(
    @SerializedName("id") val id: String,
    @SerializedName("conversationId") val conversationId: String,
    @SerializedName("role") val role: String,
    @SerializedName("content") val content: String,
    @SerializedName("status") val status: String,
    @SerializedName("route") val route: String? = null,
    @SerializedName("originalQuestion") val originalQuestion: String? = null,
    @SerializedName("errorMessage") val errorMessage: String? = null,
    @SerializedName("clientRequestId") val clientRequestId: String? = null,
    @SerializedName("clientCreatedAt") val clientCreatedAt: Long,
    @SerializedName("syncVersion") val syncVersion: Long,
    @SerializedName("createdAt") val createdAt: Long,
    @SerializedName("updatedAt") val updatedAt: Long
)

// ============================================================
// Session context (summary + facts) — GET/PUT /api/session/context
// ============================================================

/** GET /api/session/context?Id=... response data */
data class SessionContextData(
    @SerializedName("summary")
    val summary: String,
    @SerializedName("facts")
    val facts: String
)

/** PUT /api/session/context request body */
data class UpdateSessionContextRequest(
    @SerializedName("Id")
    val id: String,
    @SerializedName("summary")
    val summary: String? = null,
    @SerializedName("facts")
    val facts: String? = null
)

/** PUT /api/session/context response data */
data class UpdateSessionContextResponse(
    @SerializedName("ok")
    val ok: Boolean
)
