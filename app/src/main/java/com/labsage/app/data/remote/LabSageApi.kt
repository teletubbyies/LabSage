package com.labsage.app.data.remote

import okhttp3.MultipartBody
import retrofit2.http.*

/**
 * Retrofit service interface for non-streaming API endpoints.
 *
 * SSE streaming is handled separately via [SseDataSource] using OkHttp directly.
 * Retrofit is only used for standard request-response endpoints where its
 * declarative model (annotations, automatic JSON/multipart serialization) adds value.
 */
interface LabSageApi {

    /** Non-streaming (quick mode) chat — POST /api/chat */
    @POST("api/chat")
    suspend fun chat(@Body request: ChatRequest): ApiResponse<ChatData>

    /** File upload to knowledge base — POST /api/upload */
    @Multipart
    @POST("api/upload")
    suspend fun uploadFile(@Part file: MultipartBody.Part): ApiResponse<UploadData>

    /** Get session summary and facts — GET /api/session/context?Id=... */
    @GET("api/session/context")
    suspend fun getSessionContext(@Query("Id") id: String): ApiResponse<SessionContextData>

    /** Update session summary or facts — PUT /api/session/context */
    @PUT("api/session/context")
    suspend fun updateSessionContext(@Body request: UpdateSessionContextRequest): ApiResponse<UpdateSessionContextResponse>
}
