package com.labsage.app.data.auth

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.google.gson.Gson
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Encrypts the complete auth session with a non-exportable Android Keystore key. */
class KeystoreAuthSessionStore(
    context: Context,
    private val gson: Gson
) : AuthSessionStore {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private var cachedSession: AuthSession? = null
    private var cacheLoaded = false

    @Synchronized
    override fun load(): AuthSession? {
        if (cacheLoaded) return cachedSession
        cachedSession = runCatching {
            preferences.getString(SESSION_KEY, null)?.let(::decrypt)?.let {
                gson.fromJson(it, AuthSession::class.java)
            }
        }.getOrElse {
            preferences.edit().remove(SESSION_KEY).apply()
            null
        }
        cacheLoaded = true
        return cachedSession
    }

    @Synchronized
    override fun save(session: AuthSession) {
        val encrypted = encrypt(gson.toJson(session))
        check(preferences.edit().putString(SESSION_KEY, encrypted).commit()) {
            "Failed to persist encrypted auth session"
        }
        cachedSession = session
        cacheLoaded = true
    }

    @Synchronized
    override fun clear() {
        check(preferences.edit().remove(SESSION_KEY).commit()) {
            "Failed to clear encrypted auth session"
        }
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
        const val PREFERENCES_NAME = "labsage_secure_auth"
        const val SESSION_KEY = "encrypted_session"
        const val KEY_ALIAS = "labsage_auth_session_key_v1"
        const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_TAG_LENGTH_BITS = 128
    }
}
