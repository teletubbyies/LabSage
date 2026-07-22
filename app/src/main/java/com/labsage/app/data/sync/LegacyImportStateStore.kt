package com.labsage.app.data.sync

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import java.security.MessageDigest

private val Context.legacyImportDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "labsage_legacy_import"
)

interface LegacyImportCompletionStore {
    suspend fun isCompleted(userId: String, serverKey: String): Boolean
    suspend fun markCompleted(userId: String, serverKey: String)
}

class DataStoreLegacyImportCompletionStore(
    context: Context
) : LegacyImportCompletionStore {
    private val dataStore = context.applicationContext.legacyImportDataStore

    override suspend fun isCompleted(userId: String, serverKey: String): Boolean =
        dataStore.data.first()[completionKey(userId, serverKey)] ?: false

    override suspend fun markCompleted(userId: String, serverKey: String) {
        dataStore.edit { preferences ->
            preferences[completionKey(userId, serverKey)] = true
        }
    }

    private fun completionKey(userId: String, serverKey: String) = booleanPreferencesKey(
        "legacy_import_completed_${sha256("$serverKey\u0000$userId")}"
    )

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}
