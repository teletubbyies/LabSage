package com.labsage.app.domain.model

/**
 * State machine for streaming chat responses.
 *
 *   Idle → Streaming → Completed
 *                    → Error
 *
 * Error may be partial (some content received before interruption)
 * or complete failure (no content received).
 */
sealed class StreamState {
    /** No active stream — idle or waiting for response */
    data object Idle : StreamState()

    /** Stream is actively receiving chunks from the backend */
    data object Streaming : StreamState()

    /** Stream completed successfully */
    data class Completed(
        val fullAnswer: String,
        val route: String? = null,
        val toolStats: Map<String, Int> = emptyMap()
    ) : StreamState()

    /** Stream ended with an error */
    data class Error(
        val message: String,
        /** True if some content was received before the error */
        val partial: Boolean = false,
        /** True if the backend indicates the stream can be resumed */
        val retryable: Boolean = true,
        /** Content received so far (for resume via Continue + PartialAnswer) */
        val partialAnswer: String = "",
        /** The user question that triggered this stream */
        val lastQuestion: String = ""
    ) : StreamState()
}
