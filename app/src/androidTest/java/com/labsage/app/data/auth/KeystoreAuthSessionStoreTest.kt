package com.labsage.app.data.auth

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.gson.Gson
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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

    @Test
    fun compareAndSetCannotOverwriteOrClearASwitchedAccount() {
        val alice = session("user-a", "alice", "alice-refresh", "alice-access")
        val refreshedAlice = alice.copy(
            tokens = alice.tokens.copy(accessToken = "alice-refreshed")
        )
        val bob = session("user-b", "bob", "bob-refresh", "bob-access")
        store.save(alice)

        assertTrue(store.saveIfCurrent(alice, refreshedAlice))
        store.save(bob)
        assertFalse(store.saveIfCurrent(refreshedAlice, alice))
        assertFalse(store.clearIfCurrent(refreshedAlice))
        assertEquals(bob, store.load())
    }

    private fun session(
        userId: String,
        username: String,
        refreshToken: String,
        accessToken: String
    ) = AuthSession(
        user = AuthUser(userId, username),
        tokens = AuthTokens(
            accessToken = accessToken,
            refreshToken = refreshToken,
            accessExpiresAt = Long.MAX_VALUE - 1,
            refreshExpiresAt = Long.MAX_VALUE
        ),
        serverBaseUrl = "https://private-backend.example/"
    )
}
