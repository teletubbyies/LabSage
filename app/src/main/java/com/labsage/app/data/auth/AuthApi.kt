package com.labsage.app.data.auth

import com.labsage.app.data.remote.ApiResponse
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST

interface AuthApi {
    @POST("api/auth/register")
    suspend fun register(@Body credentials: AuthCredentials): ApiResponse<AuthSession>

    @POST("api/auth/login")
    suspend fun login(@Body credentials: AuthCredentials): ApiResponse<AuthSession>

    @POST("api/auth/refresh")
    suspend fun refresh(@Body request: RefreshTokenRequest): ApiResponse<AuthSession>

    @POST("api/auth/logout")
    suspend fun logout(@Body request: RefreshTokenRequest): ApiResponse<LogoutResponse>

    @GET("api/auth/me")
    suspend fun me(): ApiResponse<AuthUser>
}
