package com.labsage.app.data.auth

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import retrofit2.HttpException

interface AuthGateway {
    val state: StateFlow<AuthenticationState>
    suspend fun login(username: String, password: String): Result<Unit>
    suspend fun register(username: String, password: String): Result<Unit>
    suspend fun logout()
}

class AuthRepository(
    private val apiProvider: (String) -> AuthApi,
    private val sessionStore: AuthSessionStore,
    private val baseUrlProvider: () -> String,
    private val nowMillis: () -> Long = System::currentTimeMillis
) : AuthGateway {
    private val refreshMutex = Mutex()
    private val _state = MutableStateFlow<AuthenticationState>(AuthenticationState.Initializing)
    override val state: StateFlow<AuthenticationState> = _state.asStateFlow()

    init {
        val restored = sessionStore.load()
        if (restored == null || restored.serverBaseUrl.isNullOrBlank() || restored.tokens.refreshExpiresAt <= nowMillis()) {
            sessionStore.clear()
            _state.value = AuthenticationState.Unauthenticated
        } else {
            _state.value = AuthenticationState.Authenticated(restored.user)
        }
    }

    override suspend fun login(username: String, password: String): Result<Unit> = authenticate {
        val baseUrl = baseUrlProvider()
        baseUrl to apiProvider(baseUrl).login(AuthCredentials(username.trim(), password))
    }

    override suspend fun register(username: String, password: String): Result<Unit> = authenticate {
        val baseUrl = baseUrlProvider()
        baseUrl to apiProvider(baseUrl).register(AuthCredentials(username.trim(), password))
    }

    private suspend fun authenticate(
        call: suspend () -> Pair<String, com.labsage.app.data.remote.ApiResponse<AuthSession>>
    ): Result<Unit> {
        return runCatching {
            val (baseUrl, response) = call()
            val session = (response.data ?: error(response.message)).copy(serverBaseUrl = baseUrl)
            sessionStore.save(session)
            _state.value = AuthenticationState.Authenticated(session.user)
        }.mapError()
    }

    /** Called by OkHttp Authenticator. The failed token prevents redundant concurrent refreshes. */
    suspend fun refreshAfterUnauthorized(failedAccessToken: String, requestUrl: HttpUrl): Boolean = refreshMutex.withLock {
        val current = sessionStore.load()
        if (current == null) {
            expireSession()
            return false
        }
        val serverBaseUrl = current.serverBaseUrl
        if (serverBaseUrl.isNullOrBlank() || !sameOrigin(serverBaseUrl.toHttpUrl(), requestUrl)) {
            return false
        }
        if (current.tokens.accessToken != failedAccessToken && current.tokens.accessExpiresAt > nowMillis()) {
            return true
        }
        if (current.tokens.refreshExpiresAt <= nowMillis()) {
            expireSession()
            return false
        }
        return try {
            val response = apiProvider(serverBaseUrl).refresh(RefreshTokenRequest(current.tokens.refreshToken))
            val refreshed = (response.data ?: error(response.message)).copy(serverBaseUrl = serverBaseUrl)
            sessionStore.save(refreshed)
            _state.value = AuthenticationState.Authenticated(refreshed.user)
            true
        } catch (_: Exception) {
            expireSession()
            false
        }
    }

    fun accessTokenFor(requestUrl: HttpUrl): String? {
        val session = sessionStore.load() ?: return null
        val serverBaseUrl = session.serverBaseUrl?.takeIf { it.isNotBlank() } ?: return null
        return session.tokens.accessToken.takeIf { sameOrigin(serverBaseUrl.toHttpUrl(), requestUrl) }
    }

    fun sessionBaseUrl(): String? = sessionStore.load()?.serverBaseUrl

    override suspend fun logout() {
        val refreshToken = sessionStore.load()?.tokens?.refreshToken
        try {
            val baseUrl = sessionStore.load()?.serverBaseUrl
            if (refreshToken != null && !baseUrl.isNullOrBlank()) {
                apiProvider(baseUrl).logout(RefreshTokenRequest(refreshToken))
            }
        } catch (_: Exception) {
            // Local logout must still complete when the backend is unavailable.
        } finally {
            expireSession()
        }
    }

    private fun expireSession() {
        sessionStore.clear()
        _state.value = AuthenticationState.Unauthenticated
    }

    private fun <T> Result<T>.mapError(): Result<T> = fold(
        onSuccess = { Result.success(it) },
        onFailure = { throwable ->
            val message = when ((throwable as? HttpException)?.code()) {
                400 -> "用户名需为 3–64 个字符，密码需为 8–72 个字节"
                401 -> "用户名或密码错误"
                409 -> "用户名已存在"
                else -> throwable.message ?: "认证服务暂时不可用"
            }
            Result.failure(IllegalStateException(message, throwable))
        }
    )

    private fun sameOrigin(first: HttpUrl, second: HttpUrl): Boolean =
        first.scheme == second.scheme && first.host == second.host && first.port == second.port
}
