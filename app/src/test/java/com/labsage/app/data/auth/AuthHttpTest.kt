package com.labsage.app.data.auth

import com.labsage.app.data.remote.ApiResponse
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class AuthHttpTest {
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `interceptor adds bearer token`() {
        server.enqueue(MockResponse().setResponseCode(200))
        val baseUrl = server.url("/").toString()
        val store = HttpTestStore(httpSession("access-1", baseUrl))
        val repository = httpRepository(HttpFakeAuthApi(), store, baseUrl)
        val client = OkHttpClient.Builder()
            .addInterceptor(AccessTokenInterceptor(repository))
            .build()

        client.newCall(Request.Builder().url(server.url("/chat")).build()).execute().close()

        assertEquals("Bearer access-1", server.takeRequest().getHeader("Authorization"))
    }

    @Test
    fun `authenticator refreshes once and retries with new token`() {
        server.enqueue(MockResponse().setResponseCode(401))
        server.enqueue(MockResponse().setResponseCode(200))
        val baseUrl = server.url("/").toString()
        val store = HttpTestStore(httpSession("old-access", baseUrl))
        val api = HttpFakeAuthApi().apply { nextSession = httpSession("new-access", baseUrl) }
        val repository = httpRepository(api, store, baseUrl)
        val client = OkHttpClient.Builder()
            .addInterceptor(AccessTokenInterceptor(repository))
            .authenticator(AccessTokenAuthenticator(repository))
            .build()

        val response = client.newCall(Request.Builder().url(server.url("/chat")).build()).execute()

        assertEquals(200, response.code)
        response.close()
        assertEquals("Bearer old-access", server.takeRequest().getHeader("Authorization"))
        assertEquals("Bearer new-access", server.takeRequest().getHeader("Authorization"))
        assertEquals(1, api.refreshCalls)
    }

    @Test
    fun `restored custom-origin token is never sent to another backend`() {
        val otherBackend = MockWebServer().apply { start() }
        try {
            server.enqueue(MockResponse().setResponseCode(200))
            otherBackend.enqueue(MockResponse().setResponseCode(200))
            val customBaseUrl = server.url("/").toString()
            val store = HttpTestStore(httpSession("custom-token", customBaseUrl))
            val repository = httpRepository(HttpFakeAuthApi(), store, customBaseUrl)
            val client = OkHttpClient.Builder()
                .addInterceptor(AccessTokenInterceptor(repository))
                .build()

            client.newCall(Request.Builder().url(otherBackend.url("/chat")).build()).execute().close()
            client.newCall(Request.Builder().url(server.url("/chat")).build()).execute().close()

            assertEquals(null, otherBackend.takeRequest().getHeader("Authorization"))
            assertEquals("Bearer custom-token", server.takeRequest().getHeader("Authorization"))
        } finally {
            otherBackend.shutdown()
        }
    }
}

private fun httpSession(accessToken: String, baseUrl: String) = AuthSession(
    AuthUser("user-1", "alice"),
    AuthTokens(accessToken, "refresh", 9_000, 10_000),
    serverBaseUrl = baseUrl
)

private fun httpRepository(api: HttpFakeAuthApi, store: AuthSessionStore, baseUrl: String) = AuthRepository(
    apiProvider = { api },
    sessionStore = store,
    baseUrlProvider = { baseUrl },
    nowMillis = { 1_000 }
)

private class HttpTestStore(initial: AuthSession?) : AuthSessionStore {
    private var value = initial
    override fun load(): AuthSession? = value
    override fun save(session: AuthSession) { value = session }
    override fun clear() { value = null }
}

private class HttpFakeAuthApi : AuthApi {
    var nextSession = httpSession("new-access", "http://localhost/")
    var refreshCalls = 0
    override suspend fun register(credentials: AuthCredentials) = ApiResponse("OK", nextSession)
    override suspend fun login(credentials: AuthCredentials) = ApiResponse("OK", nextSession)
    override suspend fun refresh(request: RefreshTokenRequest): ApiResponse<AuthSession> {
        refreshCalls++
        return ApiResponse("OK", nextSession)
    }
    override suspend fun logout(request: RefreshTokenRequest) = ApiResponse("OK", LogoutResponse(true))
    override suspend fun me() = ApiResponse("OK", nextSession.user)
}
