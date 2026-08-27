package com.labsage.app.data.auth

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.google.gson.Gson
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

// A corrupt .preferences_pb must degrade to "no session" (like the old SharedPreferences
// falling back to an empty map) — the default NoOp handler would rethrow forever, which
// would crash AuthRepository's constructor on every launch.
private val Context.authSessionDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "labsage_secure_auth",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() }
)

/**
 * Encrypts the complete auth session with a non-exportable Android Keystore key.
 *
 * Storage is Preferences DataStore (single process-wide instance via the delegate).
 * The [AuthSessionStore] API stays synchronous because it is called from OkHttp
 * interceptor threads and AuthRepository's constructor; blocking reads/writes on
 * first access mirror the old SharedPreferences.get/commit profile, and everything
 * after that is served from the in-memory cache.
 */
class KeystoreAuthSessionStore(
    private val context: Context,
    private val gson: Gson
) : AuthSessionStore {
    private val dataStore = context.authSessionDataStore
    private var cachedSession: AuthSession? = null
    private var cacheLoaded = false

    init {
        migrateLegacySharedPreferences()
    }

    /**
     * One-time migration from the pre-DataStore SharedPreferences backing store
     * (official SP→DataStore migration pattern): copy the encrypted blob as-is —
     * the Keystore key is unchanged, so it still decrypts — then delete the file.
     */
    private fun migrateLegacySharedPreferences() {
        val legacy = context.getSharedPreferences(LEGACY_PREFERENCES_NAME, Context.MODE_PRIVATE)
        val legacyValue = legacy.getString(SESSION_KEY.name, null)
        if (legacyValue == null) {
            // No session to carry over — still remove any leftover empty legacy file.
            context.deleteSharedPreferences(LEGACY_PREFERENCES_NAME)
            return
        }
        // Guard inside the transform: edits are atomically serialized by the DataStore
        // actor, so a concurrent save can never be clobbered by the stale legacy blob.
        runBlocking {
            dataStore.edit { prefs ->
                if (prefs[SESSION_KEY] == null) prefs[SESSION_KEY] = legacyValue
            }
        }
        context.deleteSharedPreferences(LEGACY_PREFERENCES_NAME)
    }

    @Synchronized
    override fun load(): AuthSession? {
        if (cacheLoaded) return cachedSession
        // Raw read first: a transient DataStore read failure must surface as "no
        // session" WITHOUT the destructive remove — that only runs when a stored
        // blob is present but undecryptable/unparseable (self-heal).
        val stored = runCatching {
            runBlocking { dataStore.data.first() }[SESSION_KEY]
        }.getOrNull()
        cachedSession = stored?.let { blob ->
            runCatching {
                gson.fromJson(decrypt(blob), AuthSession::class.java)
            }.getOrElse {
                runCatching { runBlocking { dataStore.edit { it.remove(SESSION_KEY) } } }
                null
            }
        }
        cacheLoaded = true
        return cachedSession
    }

    @Synchronized
    override fun save(session: AuthSession) {
        val encrypted = encrypt(gson.toJson(session))
        runBlocking { dataStore.edit { it[SESSION_KEY] = encrypted } }
        cachedSession = session
        cacheLoaded = true
    }

    @Synchronized
    override fun clear() {
        runBlocking { dataStore.edit { it.remove(SESSION_KEY) } }
        cachedSession = null
        cacheLoaded = true
    }

    @Synchronized
    override fun saveIfCurrent(expected: AuthSession, session: AuthSession): Boolean {
        if (!sameSession(load(), expected)) return false
        save(session)
        return true
    }

    @Synchronized
    override fun clearIfCurrent(expected: AuthSession): Boolean {
        if (!sameSession(load(), expected)) return false
        clear()
        return true
    }

    private fun sameSession(current: AuthSession?, expected: AuthSession): Boolean =
        current != null && current.user.id == expected.user.id &&
            current.serverBaseUrl == expected.serverBaseUrl &&
            current.tokens.refreshToken == expected.tokens.refreshToken

    private fun encrypt(plaintext: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val iv = Base64.encodeToString(cipher.iv, Base64.NO_WRAP)
        val ciphertext = Base64.encodeToString(cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
        return "$iv:$ciphertext"
    }

    private fun decrypt(encoded: String): String {
        val parts = encoded.split(':', limit = 2)
        require(parts.size == 2) { "Malformed encrypted auth session" }
        val iv = Base64.decode(parts[0], Base64.NO_WRAP)
        val ciphertext = Base64.decode(parts[1], Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv))
        return cipher.doFinal(ciphertext).toString(Charsets.UTF_8)
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER).run {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .build()
            )
            generateKey()
        }
    }

    private companion object {
        const val LEGACY_PREFERENCES_NAME = "labsage_secure_auth"
        val SESSION_KEY = stringPreferencesKey("encrypted_session")
        const val KEY_ALIAS = "labsage_auth_session_key_v1"
        const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_TAG_LENGTH_BITS = 128
    }
}
