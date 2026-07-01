package com.labsage.app

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.room.Room
import com.google.gson.Gson
import com.labsage.app.data.local.AppDatabase
import com.labsage.app.data.remote.LabSageApi
import com.labsage.app.data.remote.OkHttpSseDataSource
import com.labsage.app.data.remote.SseDataSource
import com.labsage.app.data.repository.ChatRepository
import com.labsage.app.ui.chat.ChatViewModel
import com.labsage.app.ui.conversation.ConversationListViewModel
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

/**
 * Manual dependency injection container.
 *
 * No Hilt/Dagger/Koin — simple, explicit, debuggable.
 * Each dependency is a lazy property; ViewModels are created via factory methods.
 *
 * Two OkHttpClient instances share the same connection pool via newBuilder():
 *   - apiClient: 30s timeouts, body-level logging (for non-streaming endpoints)
 *   - sseClient:  no read timeout, header-level logging only (BODY would buffer the SSE stream)
 */
class LabSageApplication : Application() {

    // ============================================================
    // Configuration — change these for different environments
    // ============================================================

    companion object {
        /** Android emulator → host localhost. Change to LAN IP for physical device. */
        const val BASE_URL = "http://10.0.2.2:6872/"
    }

    // ============================================================
    // JSON
    // ============================================================

    val gson: Gson by lazy { Gson() }

    // ============================================================
    // OkHttp — shared connection pool via newBuilder()
    // ============================================================

    /** Base OkHttp client with shared config (connection pool, interceptors) */
    private val baseOkHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .addInterceptor(HttpLoggingInterceptor().apply {
                level = HttpLoggingInterceptor.Level.HEADERS
            })
            .build()
    }

    /** OkHttp client for Retrofit — 30s timeouts, body logging OK for short requests */
    val apiOkHttpClient: OkHttpClient by lazy {
        baseOkHttpClient.newBuilder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    /** OkHttp client for SSE — no read timeout (stream is long-lived), headers-only logging */
    val sseOkHttpClient: OkHttpClient by lazy {
        baseOkHttpClient.newBuilder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)   // no timeout for SSE
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    // ============================================================
    // Retrofit — for non-streaming endpoints only
    // ============================================================

    val retrofit: Retrofit by lazy {
        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(apiOkHttpClient)
            .addConverterFactory(GsonConverterFactory.create(gson))
            .build()
    }

    // ============================================================
    // API services
    // ============================================================

    val api: LabSageApi by lazy { retrofit.create(LabSageApi::class.java) }

    val sseDataSource: SseDataSource by lazy {
        OkHttpSseDataSource(sseOkHttpClient, BASE_URL.trimEnd('/'), gson)
    }

    // ============================================================
    // Room database
    // ============================================================

    val database: AppDatabase by lazy {
        Room.databaseBuilder(this, AppDatabase::class.java, "labsage.db")
            .build()
    }

    // ============================================================
    // Repository
    // ============================================================

    val repository: ChatRepository by lazy {
        ChatRepository(
            api = api,
            sseDataSource = sseDataSource,
            conversationDao = database.conversationDao(),
            messageDao = database.messageDao()
        )
    }

    // ============================================================
    // ViewModel factories
    // ============================================================

    fun chatViewModelFactory(conversationId: String): ViewModelProvider.Factory {
        return object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                if (modelClass.isAssignableFrom(ChatViewModel::class.java)) {
                    return ChatViewModel(repository, conversationId) as T
                }
                throw IllegalArgumentException("Unknown ViewModel: ${modelClass.name}")
            }
        }
    }

    val conversationListViewModelFactory: ViewModelProvider.Factory by lazy {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                if (modelClass.isAssignableFrom(ConversationListViewModel::class.java)) {
                    return ConversationListViewModel(repository) as T
                }
                throw IllegalArgumentException("Unknown ViewModel: ${modelClass.name}")
            }
        }
    }
}
