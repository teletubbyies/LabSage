package com.labsage.app.ui.chat

import com.labsage.app.data.local.entity.MessageEntity

internal data class RecoveredChatTurn(
    val user: MessageEntity,
    val assistant: MessageEntity
)

internal object ChatRecovery {
    fun from(messages: List<MessageEntity>): RecoveredChatTurn? {
        val assistant = messages.lastOrNull { it.role == "assistant" } ?: return null
        if (assistant.status !in setOf("streaming", "partial", "failed")) return null
        if (assistant.status != "failed" && assistant.content.isEmpty()) return null
        if (assistant.clientRequestId.isNullOrBlank()) return null
        val user = messages.lastOrNull {
            it.role == "user" && it.clientRequestId == assistant.clientRequestId
        } ?: return null
        return RecoveredChatTurn(user = user, assistant = assistant)
    }
}
