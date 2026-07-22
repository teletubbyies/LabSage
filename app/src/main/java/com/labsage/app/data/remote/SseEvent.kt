package com.labsage.app.data.remote

/**
 * Parsed SSE event from the backend stream.
 *
 * The Go backend (internal/logic/sse/sse.go) emits 6 event types:
 *   connected → message* → trace* → trace_summary → done | error
 */
sealed class SseEvent {
    /** Initial connection confirmation. data = JSON {status, client_id} */
    data class Connected(val status: String, val clientId: String) : SseEvent()

    /** Streaming text chunk. data is raw text (NOT JSON). */
    data class Message(val text: String) : SseEvent()

    /** Debug/log line from the ReAct agent loop. */
    data class Trace(val line: String) : SseEvent()

    /** Final tool call statistics. data = JSON {tool_stats, total_tool_calls, trace_count} */
    data class TraceSummary(
        val toolStats: Map<String, Int>,
        val totalToolCalls: Int,
        val traceCount: Int
    ) : SseEvent()

    /** Terminal metadata for completed, partial, or failed generation. */
    data class Done(
        val conversationId: String,
        val userMessageId: String,
        val assistantMessageId: String,
        val status: String,
        val updatedAt: Long,
        val syncVersion: Long
    ) : SseEvent()

    /** Stream error. data = JSON {message, partial, retryable} */
    data class Error(
        val message: String,
        val partial: Boolean = false,
        val retryable: Boolean = true
    ) : SseEvent()
}
