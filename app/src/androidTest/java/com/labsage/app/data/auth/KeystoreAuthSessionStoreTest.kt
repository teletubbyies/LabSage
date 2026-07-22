package com.labsage.app.data.auth

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.gson.Gson
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class KeystoreAuthSessionStoreTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val store = KeystoreAuthSessionStore(context, Gson())

    @After
    fun cleanUp() {
        store.clear()
    }

    @Test
    fun encryptedPreferencesNeverContainPlaintextTokens() {
        val session = AuthSession(
            user = AuthUser("user-1", "alice"),
            tokens = AuthTokens(
                accessToken = "plain-access-token",
                refreshToken = "plain-refresh-token",
                accessExpiresAt = Long.MAX_VALUE - 1,
                refreshExpiresAt = Long.MAX_VALUE
            ),
            serverBaseUrl = "https://private-backend.example/"
        )

        store.save(session)

        val rawPreferences = context
            .getSharedPreferences("labsage_secure_auth", Context.MODE_PRIVATE)
            .all
            .values
            .joinToString()
        assertFalse(rawPreferences.contains("plain-access-token"))
        assertFalse(rawPreferences.contains("plain-refresh-token"))
        assertFalse(rawPreferences.contains("private-backend.example"))
        val restoredByNewProcessLikeStore = KeystoreAuthSessionStore(context, Gson()).load()
        assertEquals(session, restoredByNewProcessLikeStore)
    }
}
