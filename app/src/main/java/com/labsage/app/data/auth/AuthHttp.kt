package com.labsage.app.data.auth

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okhttp3.Authenticator
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route

// add AccessToken
class AccessTokenInterceptor(
    private val repository: AuthRepository
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        if (original.header("Authorization") != null) return chain.proceed(original)
        val accessToken = repository.accessTokenFor(original.url) ?: return chain.proceed(original)
        return chain.proceed(original.newBuilder().bearer(accessToken).build())
    }
}

class AccessTokenAuthenticator(
    private val repository: AuthRepository
) : Authenticator {
    override fun authenticate(route: Route?, response: Response): Request? {
        if (responseCount(response) >= 2) return null
        val failedToken = response.request.header("Authorization")
            ?.removePrefix("Bearer ")
            ?.takeIf { it.isNotBlank() }
            ?: return null
        val refreshed = runBlocking(Dispatchers.IO) {
            repository.refreshAfterUnauthorized(failedToken, response.request.url)
        }
        val newToken = repository.accessTokenFor(response.request.url)
        if (!refreshed || newToken.isNullOrBlank() || newToken == failedToken) return null
        return response.request.newBuilder().bearer(newToken).build()
    }

    private fun responseCount(response: Response): Int {
        var count = 1
        var prior = response.priorResponse
        while (prior != null) {
            count++
            prior = prior.priorResponse
        }
        return count
    }
}

private fun Request.Builder.bearer(token: String): Request.Builder =
    header("Authorization", "Bearer $token")
