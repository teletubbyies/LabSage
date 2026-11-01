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

    /** Crash report upload — anonymous-tolerant so pre-login crashes report too. */
    @POST("api/crash_reports")
    suspend fun reportCrash(@Body request: CrashReportRequest): ApiResponse<CrashReportData>

    /** Non-streaming (quick mode) chat — POST /api/chat */
    @POST("api/chat")
    suspend fun chat(@Body request: ChatRequest): ApiResponse<ChatData>

    @GET("api/conversations")
    suspend fun getConversations(
        @Query("updatedAfter") updatedAfter: Long? = null
    ): ApiResponse<ConversationListData>

    @GET("api/conversations/{id}/messages")
    suspend fun getMessages(
        @Path("id") conversationId: String,
        @Query("updatedAfter") updatedAfter: Long? = null
    ): ApiResponse<MessageListData>

    @PATCH("api/conversations/{id}")
    suspend fun patchConversation(
        @Path("id") conversationId: String,
        @Body request: ConversationPatchRequest
    ): ApiResponse<ConversationDto>

    @DELETE("api/conversations/{id}")
    suspend fun deleteConversation(
        @Path("id") conversationId: String,
        @Query("version") version: Long
    ): ApiResponse<ConversationDto>

    @POST("api/sync/import")
    suspend fun importLegacyHistory(@Body request: LegacyImportRequest): ApiResponse<LegacyImportData>

    /** File upload to knowledge base — POST /api/upload */
    @Multipart
    @POST("api/upload")
    suspend fun uploadFile(@Part file: MultipartBody.Part): ApiResponse<UploadData>

    /** List all knowledge-base files — GET /api/files */
    @GET("api/files")
    suspend fun listFiles(): ApiResponse<FileListData>

    /** Delete a knowledge-base file by stable id — DELETE /api/files/{id} */
    @DELETE("api/files/{id}")
    suspend fun deleteFile(@Path("id") id: String): ApiResponse<Unit>

    /** Get session summary and facts — GET /api/session/context?Id=... */
    @GET("api/session/context")
    suspend fun getSessionContext(@Query("Id") id: String): ApiResponse<SessionContextData>

    /** Update session summary or facts — PUT /api/session/context */
    @PUT("api/session/context")
    suspend fun updateSessionContext(@Body request: UpdateSessionContextRequest): ApiResponse<UpdateSessionContextResponse>
}
