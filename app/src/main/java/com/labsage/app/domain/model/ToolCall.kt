package com.labsage.app.domain.model

import androidx.compose.runtime.Immutable

/**
 * Status of a single tool invocation observed from the SSE trace stream.
 */
enum class ToolCallStatus {
    /** Backend emitted `[tool] 开始调用 #N name args=...` but not yet the matching `调用完成`. */
    CALLING,

    /** Backend emitted `[tool] 调用完成 name result=...`. */
    COMPLETED,

    /** The stream ended while this call was still in CALLING state (interrupted). */
    ERROR
}

/**
 * Structured representation of one tool call, parsed from raw SSE trace lines.
 *
 * The backend (`utility/log_call_back/log_call_back.go`) emits:
 *   `[tool] 开始调用 #N <name> args=<compactJSON, 160>`
 *   `[tool] 调用完成 <name> result=<compactJSON, 400>`
 *
 * Because the completion line carries no call index, calls are correlated by name
 * (FIFO match against the oldest CALLING entry with the same name).
 *
 * @param key      stable unique id (used as LazyColumn key)
 * @param index    call number (#N) from the trace, or sequential fallback
 * @param name     tool name
 * @param status   CALLING / COMPLETED / ERROR
 * @param argsText raw `args=` payload (truncated by backend)
 * @param resultText raw `result=` payload (truncated by backend), empty until completed
 * @param durationMs measured wall-clock duration (0 while CALLING)
 */
@Immutable
data class ToolCallUi(
    val key: String,
    val index: Int,
    val name: String,
    val status: ToolCallStatus,
    val argsText: String,
    val resultText: String,
    val durationMs: Long
)
