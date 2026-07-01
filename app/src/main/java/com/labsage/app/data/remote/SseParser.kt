package com.labsage.app.data.remote

/**
 * Pure-function SSE line parser — zero dependencies, fully unit-testable.
 *
 * The parser reads raw SSE lines and accumulates them into events.
 * It is designed to be fed line-by-line from a BufferedReader.
 *
 * SSE wire format (from the Go backend's internal/logic/sse/sse.go):
 *   event: <type>\n
 *   data: <payload>\n
 *   \n
 *
 * Usage:
 *   val parser = SseParser()
 *   while (line != null) {
 *       val event = parser.feed(line)
 *       if (event != null) handle(event)
 *   }
 *   // After EOF, flush any remaining event:
 *   parser.flush()?.let { handle(it) }
 */
class SseParser {

    private var eventType: String = ""
    private val dataLines = mutableListOf<String>()

    /**
     * Feed one raw line from the SSE stream.
     *
     * @return A parsed [RawSseFrame] if a complete event was accumulated, null otherwise.
     */
    fun feed(line: String): RawSseFrame? {
        // Blank line → event boundary
        if (line.isEmpty()) {
            if (dataLines.isEmpty()) return null
            val event = RawSseFrame(
                event = eventType.ifEmpty { "message" },
                data = dataLines.joinToString("\n")
            )
            eventType = ""
            dataLines.clear()
            return event
        }

        // Comment line (starts with ':')
        if (line.startsWith(":")) return null

        // Dispatch by field prefix
        when {
            line.startsWith("event: ")  -> eventType = line.substring(7)
            line.startsWith("event:")   -> eventType = line.substring(6)
            line.startsWith("data: ")   -> dataLines.add(line.substring(6))
            line.startsWith("data:")    -> dataLines.add(line.substring(5))
            // id: lines are ignored (not needed for our use case)
        }
        return null
    }

    /**
     * Flush any pending event data after the stream ends.
     */
    fun flush(): RawSseFrame? {
        if (dataLines.isEmpty()) return null
        val event = RawSseFrame(
            event = eventType.ifEmpty { "message" },
            data = dataLines.joinToString("\n")
        )
        eventType = ""
        dataLines.clear()
        return event
    }
}

/**
 * Raw parsed SSE frame before domain-level interpretation.
 */
data class RawSseFrame(
    val event: String,
    val data: String
)
