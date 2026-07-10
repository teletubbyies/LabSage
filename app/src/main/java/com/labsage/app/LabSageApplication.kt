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
import com.labsage.app.data.repository.SettingsRepository
import com.labsage.app.domain.model.AppSettings
import com.labsage.app.notification.ResponseNotificationManager
import com.labsage.app.ui.chat.ChatViewModel
import com.labsage.app.ui.conversation.ConversationListViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.ConcurrentHashMap
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
 *
 * The network layer (Retrofit + SSE) is cached per base URL so the user can change
 * the backend address in Settings and new requests pick it up immediately, without
 * an app restart.
 */
class LabSageApplication : Application() {

    companion object {
        /** Default backend URL (Android emulator → host localhost). */
        const val DEFAULT_BASE_URL = AppSettings.DEFAULT_BASE_URL
    }

    override fun onCreate() {
        super.onCreate()
        responseNotificationManager.createChannel()
    }

    // ============================================================
    // Settings (DataStore)
    // ============================================================

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val settingsRepository: SettingsRepository by lazy { SettingsRepository(this) }

    /**
     * Hot [AppSettings] stream. Eagerly subscribed so [currentBaseUrl] always
     * reflects the latest persisted value; starts from defaults until DataStore
     * finishes its first read.
     *
     * MUST be lazy: resolving it touches [settingsRepository], which touches the
     * DataStore `preferencesDataStore` delegate, which calls getApplicationContext()
     * on `this`. During Application <init> the base context is not yet attached
     * (attachBaseContext runs later), so an eager initializer would NPE. Lazy defers
     * the first access to a composable / network call, well after attach.
     */
    val appSettings: StateFlow<AppSettings> by lazy {
        settingsRepository.settings
            .stateIn(appScope, SharingStarted.Eagerly, AppSettings())
    }

    private val currentBaseUrl: String get() = appSettings.value.baseUrl

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
    // Network layer — cached per base URL (hot-swappable from Settings)
    // ============================================================

    private data class NetworkLayer(val api: LabSageApi, val sse: SseDataSource)

    private val networkCache = ConcurrentHashMap<String, NetworkLayer>()

    /** Normalize a user-entered URL into a Retrofit-compatible base (trailing "/"). */
    private fun normalizeBaseUrl(raw: String): String {
        var u = raw.trim()
        if (u.isEmpty()) u = DEFAULT_BASE_URL
        if (!u.startsWith("http://") && !u.startsWith("https://")) u = "http://$u"
        if (!u.endsWith("/")) u += "/"
        return u
    }

    private fun networkLayerFor(rawUrl: String): NetworkLayer {
        val base = normalizeBaseUrl(rawUrl)
        return networkCache.getOrPut(base) {
            val retrofit = Retrofit.Builder()
                .baseUrl(base)
                .client(apiOkHttpClient)
                .addConverterFactory(GsonConverterFactory.create(gson))
                .build()
            NetworkLayer(
                api = retrofit.create(LabSageApi::class.java),
                sse = OkHttpSseDataSource(sseOkHttpClient, base.trimEnd('/'), gson)
            )
        }
    }

    /** Current [LabSageApi] for the active base URL. */
    val api: LabSageApi get() = networkLayerFor(currentBaseUrl).api

    /** Current [SseDataSource] for the active base URL. */
    val sseDataSource: SseDataSource get() = networkLayerFor(currentBaseUrl).sse

    // ============================================================
    // Room database
    // ============================================================

    val database: AppDatabase by lazy {
        Room.databaseBuilder(this, AppDatabase::class.java, "labsage.db")
            .addMigrations(AppDatabase.MIGRATION_1_2)
            .build()
    }

    // ============================================================
    // Repository
    // ============================================================

    val repository: ChatRepository by lazy {
        ChatRepository(
            app = this,
            conversationDao = database.conversationDao(),
            messageDao = database.messageDao()
        )
    }

    val responseNotificationManager: ResponseNotificationManager by lazy {
        ResponseNotificationManager(this)
    }

    // ============================================================
    // ViewModel factories
    // ============================================================

    fun chatViewModelFactory(conversationId: String): ViewModelProvider.Factory {
        return object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                if (modelClass.isAssignableFrom(ChatViewModel::class.java)) {
                    return ChatViewModel(
                        repository,
                        settingsRepository,
                        responseNotificationManager,
                        conversationId
                    ) as T
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

    val settingsViewModelFactory: ViewModelProvider.Factory by lazy {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                if (modelClass.isAssignableFrom(com.labsage.app.ui.settings.SettingsViewModel::class.java)) {
                    return com.labsage.app.ui.settings.SettingsViewModel(settingsRepository) as T
                }
                throw IllegalArgumentException("Unknown ViewModel: ${modelClass.name}")
            }
        }
    }
}
