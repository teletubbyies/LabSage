package com.labsage.app.data.remote

import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import java.io.IOException

/**
 * Production SSE data source using Square's okhttp-sse library.
 *
 * Key design decisions:
 *   - Uses callbackFlow to bridge callback-based EventSourceListener → Kotlin Flow
 *   - Runs on Dispatchers.IO so the OkHttp thread pool is not blocked
 *   - HttpLoggingInterceptor with Level.BODY must NEVER be on the SSE client
 *     (it buffers the entire response body, so onResponse never fires)
 */
class OkHttpSseDataSource(
    private val sseClient: OkHttpClient,
    private val baseUrl: String,
    private val gson: Gson
) : SseDataSource {

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    override fun streamChat(request: ChatStreamRequest): Flow<SseEvent> = callbackFlow {
        val jsonBody = gson.toJson(request)
        val httpRequest = Request.Builder()
            .url("$baseUrl/api/chat_stream")
            .post(jsonBody.toRequestBody(jsonMediaType))
            .header("Accept", "text/event-stream")
            .build()

        val factory = EventSources.createFactory(sseClient)
        val eventSource = factory.newEventSource(httpRequest, object : EventSourceListener() {

            override fun onOpen(eventSource: EventSource, response: Response) {
                // Connection established — events will follow
            }

            override fun onEvent(
                eventSource: EventSource,
                id: String?,
                type: String?,
                data: String
            ) {
                val event = buildEvent(type, data) ?: return
                // Filter trace events: forward only structured lifecycle lines used to build
                // the tool execution panel. Raw queries generate 200+ free-form trace lines;
                // keeping all of them would overflow the channel (see BUGFIX_LOG #11).
                //
                // Forwarded prefixes (all bounded — a handful per tool call, args capped at
                // 160 chars and result at 400 chars by the backend):
                //   [tool]   开始调用 #N name args=...  /  调用完成 name result=...
                //   [react]  模型决定调用工具：...
                //   [route]  route=... intent=...        /  [route_policy] ...
                if (event is SseEvent.Trace) {
                    val l = event.line
                    val keep = l.startsWith("[tool]") || l.startsWith("[react]") || l.startsWith("[route")
                    if (!keep) return
                }
                // Attempts to add the specified element to this channel without waiting.
                //trySend never suspends and never throws exceptions.
                trySend(event)
            }

            override fun onClosed(eventSource: EventSource) {
                channel.close()
            }

            override fun onFailure(
                eventSource: EventSource,
                t: Throwable?,
                response: Response?
            ) {
                eventSource.cancel()
                val message = t?.message
                    ?: "SSE connection failed: HTTP ${response?.code}"
                trySend(SseEvent.Error(message = message, retryable = true))
                close(IOException(message))
            }
        })

        awaitClose {
            eventSource.cancel()
        }
    }
        .buffer(Channel.UNLIMITED)  // prevent data loss with 240+ events on tool queries
        .flowOn(Dispatchers.IO)

    // ============================================================
    // Event parsing
    // ============================================================

    private fun buildEvent(type: String?, data: String): SseEvent? {
        return when (type) {
            "connected" -> {
                try {
                    val payload = gson.fromJson(data, ConnectedPayload::class.java)
                    SseEvent.Connected(payload.status, payload.clientId)
                } catch (e: Exception) {
                    SseEvent.Connected("unknown", "")
                }
            }
            "message" -> SseEvent.Message(data)
            "trace" -> SseEvent.Trace(data)
            "trace_summary" -> {
                try {
                    val payload = gson.fromJson(data, TraceSummaryPayload::class.java)
                    SseEvent.TraceSummary(payload.toolStats, payload.totalToolCalls, payload.traceCount)
                } catch (e: Exception) {
                    null // ignore malformed trace_summary
                }
            }
            "done" -> SseEvent.Done
            "error" -> {
                try {
                    val payload = gson.fromJson(data, SseErrorPayload::class.java)
                    SseEvent.Error(payload.message, payload.partial, payload.retryable)
                } catch (e: Exception) {
                    SseEvent.Error(data, partial = false, retryable = false)
                }
            }
            null -> SseEvent.Message(data) // default: treat as message chunk
            else -> SseEvent.Message(data) // unknown event types treated as message
        }
    }
}
