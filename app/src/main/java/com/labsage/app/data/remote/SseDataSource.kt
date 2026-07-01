package com.labsage.app.data.remote

import kotlinx.coroutines.flow.Flow

/**
 * Abstraction over SSE streaming — provides a testability seam.
 *
 * In production: OkHttpSseDataSource (okhttp-sse + callbackFlow)
 * In tests:       FakeSseDataSource (MutableSharedFlow injection)
 */
interface SseDataSource {
    /**
     * Opens an SSE connection to /api/chat_stream and returns a
     * cold Flow that emits each parsed event.
     *
     * The Flow completes when:
     *   - The server sends "event: done"
     *   - The connection is closed by either side
     *   - An unrecoverable error occurs
     *
     * The caller must collect on a coroutine scope (e.g. viewModelScope).
     */
    fun streamChat(request: ChatStreamRequest): Flow<SseEvent>
}
