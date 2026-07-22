package com.labsage.app.data.sync

import com.labsage.app.data.remote.LegacyImportRequest
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class LegacyImportBinding(
    val userId: String,
    val serverKey: String
)

fun interface LegacyImportRemoteSource {
    suspend fun import(binding: LegacyImportBinding, request: LegacyImportRequest)
}

fun interface LegacyImportLocalSource {
    suspend fun snapshot(userId: String): LegacyImportRequest
}

/** Runs the one-time import and records completion only after backend success. */
class LegacyHistoryImportCoordinator(
    private val remote: LegacyImportRemoteSource,
    private val local: LegacyImportLocalSource,
    private val completionStore: LegacyImportCompletionStore,
    private val isBindingCurrent: (LegacyImportBinding) -> Boolean
) {
    private val mutex = Mutex()

    suspend fun ensureImported(binding: LegacyImportBinding) = mutex.withLock {
        check(isBindingCurrent(binding)) { "Authentication changed before legacy import" }
        if (completionStore.isCompleted(binding.userId, binding.serverKey)) return@withLock
        val snapshot = local.snapshot(binding.userId)
        val batches = buildList {
            snapshot.conversations.chunked(CONVERSATION_BATCH_SIZE).forEach {
                add(LegacyImportRequest(conversations = it, messages = emptyList()))
            }
            snapshot.messages.chunked(MESSAGE_BATCH_SIZE).forEach {
                add(LegacyImportRequest(conversations = emptyList(), messages = it))
            }
            if (isEmpty()) add(snapshot) // Backend confirmation is still required for an empty import.
        }
        batches.forEach {
            check(isBindingCurrent(binding)) { "Authentication changed during legacy import" }
            remote.import(binding, it)
            check(isBindingCurrent(binding)) { "Authentication changed during legacy import" }
        }
        check(isBindingCurrent(binding)) { "Authentication changed before import completion" }
        completionStore.markCompleted(binding.userId, binding.serverKey)
    }

    private companion object {
        const val CONVERSATION_BATCH_SIZE = 500
        const val MESSAGE_BATCH_SIZE = 2_000
    }
}
