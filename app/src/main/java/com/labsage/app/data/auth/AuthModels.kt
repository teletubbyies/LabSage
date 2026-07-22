package com.labsage.app.data.auth

import com.google.gson.annotations.SerializedName

data class AuthCredentials(
    @SerializedName("username") val username: String,
    @SerializedName("password") val password: String
)

data class RefreshTokenRequest(
    @SerializedName("refreshToken") val refreshToken: String
)

data class AuthUser(
    @SerializedName("id") val id: String,
    @SerializedName("username") val username: String
)

data class AuthTokens(
    @SerializedName("accessToken") val accessToken: String,
    @SerializedName("refreshToken") val refreshToken: String,
    @SerializedName("accessExpiresAt") val accessExpiresAt: Long,
    @SerializedName("refreshExpiresAt") val refreshExpiresAt: Long
)

data class AuthSession(
    @SerializedName("user") val user: AuthUser,
    @SerializedName("tokens") val tokens: AuthTokens,
    /** Client-only binding; absent in server responses and stamped before persistence. */
    val serverBaseUrl: String? = null
)

data class LogoutResponse(
    @SerializedName("ok") val ok: Boolean
)

sealed interface AuthenticationState {
    data object Initializing : AuthenticationState
    data object Unauthenticated : AuthenticationState
    data class Authenticated(val user: AuthUser) : AuthenticationState
}
