package com.labsage.app.integration

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.labsage.app.data.auth.AuthApi
import com.labsage.app.data.auth.AuthCredentials
import com.labsage.app.data.remote.ConversationPatchRequest
import com.labsage.app.data.remote.LabSageApi
import com.labsage.app.data.remote.LegacyConversationDto
import com.labsage.app.data.remote.LegacyImportRequest
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.UUID

/**
 * Opt-in smoke test against a real Go backend.
 *
 * It is skipped during the normal hermetic suite. Pass the instrumentation
 * argument `realBackendBaseUrl` to prove that the Android process and the
 * production Retrofit contracts interoperate with a locally running server.
 */
@RunWith(AndroidJUnit4::class)
class RealBackendSmokeTest {
    @Test
    fun authHistoryMutationAndTombstoneRoundTrip() = runBlocking {
        val arguments = InstrumentationRegistry.getArguments()
        val baseUrl = arguments.getString("realBackendBaseUrl")?.trim().orEmpty()
        assumeTrue("realBackendBaseUrl is required for the opt-in smoke test", baseUrl.isNotEmpty())
        require(baseUrl.endsWith('/')) { "realBackendBaseUrl must end with /" }

        val retrofit = Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(OkHttpClient())
            .addConverterFactory(GsonConverterFactory.create())
            .build()
        val authApi = retrofit.create(AuthApi::class.java)
        val username = "f11-${UUID.randomUUID().toString().take(12)}"
        val session = authApi.register(AuthCredentials(username, "f11-real-backend-password"))
            .data
        assertNotNull(session)
        assertEquals(username, session!!.user.username)

        val authenticatedClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                chain.proceed(
                    chain.request().newBuilder()
                        .header("Authorization", "Bearer ${session.tokens.accessToken}")
                        .build()
                )
            }
            .build()
        val api = retrofit.newBuilder()
            .client(authenticatedClient)
            .build()
            .create(LabSageApi::class.java)

        val conversationId = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val imported = api.importLegacyHistory(
            LegacyImportRequest(
                conversations = listOf(
                    LegacyConversationDto(
                        id = conversationId,
                        title = "Android real-backend smoke",
                        isPinned = false,
                        createdAt = now,
                        updatedAt = now
                    )
                ),
                messages = emptyList()
            )
        ).data
        assertEquals(1, imported?.importedConversations)

        val fullPage = api.getConversations().data
        val created = fullPage?.items?.single { it.id == conversationId }
        assertNotNull(created)

        val patched = api.patchConversation(
            conversationId,
            ConversationPatchRequest(isPinned = true, version = created!!.version)
        ).data
        assertEquals(true, patched?.isPinned)
        assertTrue(patched!!.version > created.version)

        val deleted = api.deleteConversation(conversationId, patched.version).data
        assertEquals(true, deleted?.isDeleted)
        val delta = api.getConversations(updatedAfter = patched.syncVersion).data
        val tombstone = delta?.items?.single { it.id == conversationId }
        assertEquals(true, tombstone?.isDeleted)
        assertNotNull(tombstone?.deletedAt)
    }
}
