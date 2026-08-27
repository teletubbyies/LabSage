package com.labsage.app.data.auth

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.gson.Gson
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class KeystoreAuthSessionStoreTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val store = KeystoreAuthSessionStore(context, Gson())

    @After
    fun cleanUp() {
        store.clear()
    }

    @Test
    fun encryptedStorageNeverContainsPlaintextTokens() {
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

        // Raw bytes of the Preferences DataStore file (ISO_8859_1 = 1:1 byte mapping).
        val rawFile = File(context.filesDir, "datastore/labsage_secure_auth.preferences_pb")
        assertTrue(rawFile.exists())
        val raw = String(rawFile.readBytes(), Charsets.ISO_8859_1)
        assertFalse(raw.contains("plain-access-token"))
        assertFalse(raw.contains("plain-refresh-token"))
        assertFalse(raw.contains("private-backend.example"))
        val restoredByNewProcessLikeStore = KeystoreAuthSessionStore(context, Gson()).load()
        assertEquals(session, restoredByNewProcessLikeStore)
    }

    @Test
    fun legacySharedPreferencesAreImportedThenDeleted() {
        // Simulate an old-version install: the legacy XML store still holds a blob.
        val legacy = context.getSharedPreferences("labsage_secure_auth", Context.MODE_PRIVATE)
        legacy.edit().putString("encrypted_session", "not-a-valid-blob").commit()

        // Constructing a new store instance runs the one-time migration; the garbage
        // blob then fails decryption and self-heals to "no session", not a crash.
        val migrated = KeystoreAuthSessionStore(context, Gson())
        assertNull(migrated.load())
        assertFalse(
            context.getSharedPreferences("labsage_secure_auth", Context.MODE_PRIVATE)
                .all.containsKey("encrypted_session")
        )
    }

    @Test
    fun existingDataStoreSessionWinsOverLegacyFile() {
        val session = session("user-w", "winner", "winner-refresh", "winner-access")
        store.save(session)
        val legacy = context.getSharedPreferences("labsage_secure_auth", Context.MODE_PRIVATE)
        legacy.edit().putString("encrypted_session", "not-a-valid-blob").commit()

        val migrated = KeystoreAuthSessionStore(context, Gson())
        assertEquals(session, migrated.load())
        // Re-fetch the instance: the held `legacy` reference keeps its in-memory map
        // even after deleteSharedPreferences evicted it from the Context cache.
        assertFalse(
            context.getSharedPreferences("labsage_secure_auth", Context.MODE_PRIVATE)
                .all.containsKey("encrypted_session")
        )
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
