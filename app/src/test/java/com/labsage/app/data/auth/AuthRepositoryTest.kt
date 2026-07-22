package com.labsage.app.data.auth

import com.labsage.app.data.remote.ApiResponse
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class AuthRepositoryTest {
    private val baseUrl = "https://custom.example/"

    @Test
    fun `restores a valid encrypted session`() {
        val store = InMemoryAuthSessionStore(session(expiresAt = 2_000))
        val repository = repository(FakeAuthApi(), store)

        assertEquals(
            AuthenticationState.Authenticated(AuthUser("user-1", "alice")),
            repository.state.value
        )
    }

    @Test
    fun `expired refresh token clears restored session`() {
        val store = InMemoryAuthSessionStore(session(expiresAt = 999))
        val repository = repository(FakeAuthApi(), store)

        assertEquals(AuthenticationState.Unauthenticated, repository.state.value)
        assertNull(store.load())
    }

    @Test
    fun `restored token is bound to its original backend`() = runTest {
        val api = FakeAuthApi().apply { nextSession = session(accessToken = "new-access", expiresAt = 20_000) }
        val store = InMemoryAuthSessionStore(session(accessToken = "custom-token", expiresAt = 10_000))
        val repository = repository(api, store)
        val defaultBackend = "http://10.0.2.2:6872/".toHttpUrl()

        assertNull(repository.accessTokenFor(defaultBackend))
        assertEquals("custom-token", repository.accessTokenFor(baseUrl.toHttpUrl()))
        assertFalse(repository.refreshAfterUnauthorized("custom-token", defaultBackend))
        assertEquals(0, api.refreshCalls)
        assertTrue(repository.refreshAfterUnauthorized("custom-token", baseUrl.toHttpUrl()))
        assertEquals(baseUrl, api.lastRequestedBaseUrl)
    }

    @Test
    fun `login persists session and authenticates user`() = runTest {
        val api = FakeAuthApi().apply { nextSession = session(accessToken = "login-access") }
        val store = InMemoryAuthSessionStore()
        val repository = repository(api, store)

        assertTrue(repository.login(" alice ", "password123").isSuccess)
        assertEquals("login-access", store.load()?.tokens?.accessToken)
        assertEquals(AuthenticationState.Authenticated(AuthUser("user-1", "alice")), repository.state.value)
    }

    @Test
    fun `concurrent 401 responses perform one refresh`() = runTest {
        val old = session(accessToken = "old-access", expiresAt = 9_999)
        val api = FakeAuthApi().apply { nextSession = session(accessToken = "new-access", expiresAt = 19_999) }
        val store = InMemoryAuthSessionStore(old)
        val repository = repository(api, store)

        val results = List(8) {
            async { repository.refreshAfterUnauthorized("old-access", baseUrl.toHttpUrl()) }
        }.awaitAll()

        assertTrue(results.all { it })
        assertEquals(1, api.refreshCalls)
        assertEquals("new-access", repository.accessTokenFor(baseUrl.toHttpUrl()))
    }

    @Test
    fun `failed refresh clears local authentication`() = runTest {
        val api = FakeAuthApi().apply { refreshFailure = IOException("offline") }
        val store = InMemoryAuthSessionStore(session(expiresAt = 9_999))
        val repository = repository(api, store)

        assertFalse(repository.refreshAfterUnauthorized("access", baseUrl.toHttpUrl()))
        assertNull(store.load())
        assertEquals(AuthenticationState.Unauthenticated, repository.state.value)
    }

    @Test
    fun `bound operation refreshes expired access token for the same identity`() = runTest {
        val expiredAccess = session(accessToken = "expired", expiresAt = 10_000).copy(
            tokens = session(expiresAt = 10_000).tokens.copy(accessExpiresAt = 999)
        )
        val api = FakeAuthApi().apply {
            nextSession = session(accessToken = "fresh", expiresAt = 20_000)
        }
        val store = InMemoryAuthSessionStore(expiredAccess)
        val repository = repository(api, store)

        val refreshed = repository.sessionForBoundOperation("user-1", baseUrl)

        assertEquals("fresh", refreshed?.tokens?.accessToken)
        assertEquals(1, api.refreshCalls)
        assertEquals("fresh", store.load()?.tokens?.accessToken)
    }

    @Test
    fun `bound refresh cannot overwrite a concurrently switched account`() = runTest {
        val expiredAccess = session(accessToken = "expired", expiresAt = 10_000).copy(
            tokens = session(expiresAt = 10_000).tokens.copy(accessExpiresAt = 999)
        )
        val store = InMemoryAuthSessionStore(expiredAccess)
        val api = FakeAuthApi().apply {
            nextSession = session(accessToken = "stale-refresh", expiresAt = 20_000)
            onRefresh = {
                val bob = session(accessToken = "bob-token", expiresAt = 20_000)
                store.save(
                    bob.copy(
                        user = AuthUser("user-2", "bob"),
                        tokens = bob.tokens.copy(refreshToken = "bob-refresh")
                    )
                )
            }
        }
        val repository = repository(api, store)

        val refreshed = repository.sessionForBoundOperation("user-1", baseUrl)

        assertNull(refreshed)
        assertEquals("user-2", store.load()?.user?.id)
        assertEquals("bob-token", store.load()?.tokens?.accessToken)
    }

    @Test
    fun `logout clears local state even when backend is unavailable`() = runTest {
        val api = FakeAuthApi().apply { logoutFailure = IOException("offline") }
        val store = InMemoryAuthSessionStore(session(expiresAt = 9_999))
        val repository = repository(api, store)

        repository.logout()

        assertNull(store.load())
        assertEquals(AuthenticationState.Unauthenticated, repository.state.value)
    }

    private fun session(
        accessToken: String = "access",
        expiresAt: Long = 10_000
    ) = AuthSession(
        user = AuthUser("user-1", "alice"),
        tokens = AuthTokens(
            accessToken = accessToken,
            refreshToken = "refresh",
            accessExpiresAt = expiresAt - 1_000,
            refreshExpiresAt = expiresAt
        ),
        serverBaseUrl = baseUrl
    )

    private fun repository(api: FakeAuthApi, store: AuthSessionStore) = AuthRepository(
        apiProvider = { baseUrl -> api.also { it.lastRequestedBaseUrl = baseUrl } },
        sessionStore = store,
        baseUrlProvider = { baseUrl },
        nowMillis = { 1_000 }
    )
}

private class InMemoryAuthSessionStore(initial: AuthSession? = null) : AuthSessionStore {
    private var session = initial
    override fun load(): AuthSession? = session
    override fun save(session: AuthSession) { this.session = session }
    override fun clear() { session = null }
    @Synchronized
    override fun saveIfCurrent(expected: AuthSession, session: AuthSession): Boolean {
        if (!sameSession(this.session, expected)) return false
        this.session = session
        return true
    }
    @Synchronized
    override fun clearIfCurrent(expected: AuthSession): Boolean {
        if (!sameSession(session, expected)) return false
        session = null
        return true
    }
    private fun sameSession(current: AuthSession?, expected: AuthSession): Boolean =
        current != null && current.user.id == expected.user.id &&
            current.serverBaseUrl == expected.serverBaseUrl &&
            current.tokens.refreshToken == expected.tokens.refreshToken
}

private class FakeAuthApi : AuthApi {
    var nextSession: AuthSession = AuthSession(
        AuthUser("user-1", "alice"),
        AuthTokens("access", "refresh", 2_000, 3_000)
    )
    var refreshCalls = 0
    var refreshFailure: Exception? = null
    var logoutFailure: Exception? = null
    var lastRequestedBaseUrl: String? = null
    var onRefresh: (() -> Unit)? = null

    override suspend fun register(credentials: AuthCredentials) = ApiResponse("OK", nextSession)
    override suspend fun login(credentials: AuthCredentials) = ApiResponse("OK", nextSession)
    override suspend fun refresh(request: RefreshTokenRequest): ApiResponse<AuthSession> {
        refreshCalls++
        onRefresh?.invoke()
        refreshFailure?.let { throw it }
        return ApiResponse("OK", nextSession)
    }
    override suspend fun logout(request: RefreshTokenRequest): ApiResponse<LogoutResponse> {
        logoutFailure?.let { throw it }
        return ApiResponse("OK", LogoutResponse(true))
    }
    override suspend fun me() = ApiResponse("OK", nextSession.user)
}
