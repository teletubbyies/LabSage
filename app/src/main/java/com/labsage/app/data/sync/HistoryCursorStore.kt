package com.labsage.app.data.sync

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.security.MessageDigest

private val Context.historyCursorDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "labsage_history_cursors"
)

interface HistoryCursorStore {
    suspend fun invalidateCacheBinding(userId: String)
    fun observeConversationsCacheBound(userId: String, serverKey: String): Flow<Boolean>
    suspend fun isConversationsCacheBound(userId: String, serverKey: String): Boolean
    suspend fun getConversationsCursor(userId: String, serverKey: String): Long?
    suspend fun setConversationsCursor(userId: String, serverKey: String, cursor: Long)
    fun observeMessagesCacheBound(
        userId: String,
        serverKey: String,
        conversationId: String
    ): Flow<Boolean>
    suspend fun isMessagesCacheBound(
        userId: String,
        serverKey: String,
        conversationId: String
    ): Boolean
    suspend fun getMessagesCursor(userId: String, serverKey: String, conversationId: String): Long?
    suspend fun setMessagesCursor(
        userId: String,
        serverKey: String,
        conversationId: String,
        cursor: Long
    )
}

class DataStoreHistoryCursorStore(context: Context) : HistoryCursorStore {
    private val dataStore = context.applicationContext.historyCursorDataStore

    override suspend fun invalidateCacheBinding(userId: String) {
        dataStore.edit {
            it.remove(conversationsBindingKey(userId))
            it.remove(messagesGlobalBindingKey(userId))
        }
    }

    override fun observeConversationsCacheBound(
        userId: String,
        serverKey: String
    ): Flow<Boolean> = dataStore.data.map {
        it[conversationsBindingKey(userId)] == sha256(serverKey)
    }.distinctUntilChanged()

    override suspend fun isConversationsCacheBound(userId: String, serverKey: String): Boolean =
        dataStore.data.first()[conversationsBindingKey(userId)] == sha256(serverKey)

    override suspend fun getConversationsCursor(userId: String, serverKey: String): Long? =
        dataStore.data.first()[conversationsKey(userId, serverKey)]

    override suspend fun setConversationsCursor(userId: String, serverKey: String, cursor: Long) {
        require(cursor >= 0)
        dataStore.edit {
            it[conversationsBindingKey(userId)] = sha256(serverKey)
            it[conversationsKey(userId, serverKey)] = cursor
        }
    }

    override suspend fun isMessagesCacheBound(
        userId: String,
        serverKey: String,
        conversationId: String
    ): Boolean = dataStore.data.first().let {
        it[messagesGlobalBindingKey(userId)] == sha256(serverKey) &&
            it[messagesBindingKey(userId, conversationId)] == sha256(serverKey)
    }

    override fun observeMessagesCacheBound(
        userId: String,
        serverKey: String,
        conversationId: String
    ): Flow<Boolean> = dataStore.data.map {
        it[messagesGlobalBindingKey(userId)] == sha256(serverKey) &&
            it[messagesBindingKey(userId, conversationId)] == sha256(serverKey)
    }.distinctUntilChanged()

    override suspend fun getMessagesCursor(
        userId: String,
        serverKey: String,
        conversationId: String
    ): Long? = dataStore.data.first()[messagesKey(userId, serverKey, conversationId)]

    override suspend fun setMessagesCursor(
        userId: String,
        serverKey: String,
        conversationId: String,
        cursor: Long
    ) {
        require(cursor >= 0)
        dataStore.edit {
            it[messagesGlobalBindingKey(userId)] = sha256(serverKey)
            it[messagesBindingKey(userId, conversationId)] = sha256(serverKey)
            it[messagesKey(userId, serverKey, conversationId)] = cursor
        }
    }

    private fun conversationsBindingKey(userId: String) = stringPreferencesKey(
        "conversations_binding_${sha256(userId)}"
    )

    private fun messagesBindingKey(userId: String, conversationId: String) =
        stringPreferencesKey("messages_binding_${sha256("$userId\u0000$conversationId")}")

    private fun messagesGlobalBindingKey(userId: String) = stringPreferencesKey(
        "messages_global_binding_${sha256(userId)}"
    )

    private fun conversationsKey(userId: String, serverKey: String) = longPreferencesKey(
        "conversations_${sha256("$serverKey\u0000$userId")}",
    )

    private fun messagesKey(userId: String, serverKey: String, conversationId: String) =
        longPreferencesKey(
            "messages_${sha256("$serverKey\u0000$userId\u0000$conversationId")}",
        )

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}
