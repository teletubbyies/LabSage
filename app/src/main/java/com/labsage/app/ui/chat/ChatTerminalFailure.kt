package com.labsage.app.ui.chat

internal data class LocalTerminalFailure(
    val content: String,
    val status: String,
    val isPartial: Boolean
)

/** The accumulated answer, not an untrusted SSE flag, decides whether content is resumable. */
internal object ChatTerminalFailure {
    fun from(accumulatedAnswer: String): LocalTerminalFailure {
        val isPartial = accumulatedAnswer.isNotEmpty()
        return LocalTerminalFailure(
            content = accumulatedAnswer,
            status = if (isPartial) "partial" else "failed",
            isPartial = isPartial
        )
    }
}
