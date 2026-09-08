package com.labsage.app

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.room.Room
import com.google.gson.Gson
import com.labsage.app.data.auth.AccessTokenAuthenticator
import com.labsage.app.data.auth.AccessTokenInterceptor
import com.labsage.app.data.auth.AuthApi
import com.labsage.app.data.auth.AuthRepository
import com.labsage.app.data.auth.AuthSession
import com.labsage.app.data.auth.KeystoreAuthSessionStore
import com.labsage.app.ui.auth.AuthViewModel
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
import com.labsage.app.ui.files.FileManagementViewModel
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
 * The backend endpoint is FIXED at build time: BuildConfig.API_BASE_URL, declared per
 * build type in app/build.gradle.kts (industry-standard buildConfigField). It is not
 * runtime-editable. The network layer (Retrofit + SSE) is memoized per base URL, so in
 * practice a single instance exists for the app's lifetime.
 */
class LabSageApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        responseNotificationManager.createChannel() // 向系统注册通知channel
    }

    // ============================================================
    // Settings (DataStore)
    // ============================================================

    // 创建一个与应用进程生命周期绑定的协程作用域，用于执行不依赖具体Activity/VM的全局后台任务
    // SupervisorJob的特点：子协程失败，不会自动取消其它兄弟协程与父协程；但不会自动处理异常
    // Default适合计算密集任务
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // 传入this是为了获取应用级的datasore
    val settingsRepository: SettingsRepository by lazy { SettingsRepository(this) }

    /**
     * Hot [AppSettings] stream (theme, quick actions…). Eagerly subscribed so
     * screens always see the latest persisted value; starts from defaults until
     * DataStore finishes its first read.
     *
     * MUST be lazy: resolving it touches [settingsRepository], which touches the
     * DataStore `preferencesDataStore` delegate, which calls getApplicationContext()
     * on `this`. During Application <init> the base context is not yet attached
     * (attachBaseContext runs later), so an eager initializer would NPE. Lazy defers
     * the first access to a composable / network call, well after attach.
     */
    // 变应用级别的热流，持续监听
    val appSettings: StateFlow<AppSettings> by lazy {
        settingsRepository.settings
            .stateIn(appScope, SharingStarted.Eagerly, AppSettings())
    }

    /** Fixed backend endpoint — single source of truth is BuildConfig.API_BASE_URL. */
    private val currentBaseUrl: String
        get() = BuildConfig.API_BASE_URL

    // ============================================================
    // JSON
    // ============================================================

    val gson: Gson by lazy { Gson() } // 用在keystore中对json对序列化/反序列化

    // ============================================================
    // Authentication — encrypted session + isolated refresh client
    // ============================================================

    private val authSessionStore by lazy { KeystoreAuthSessionStore(this, gson) }

    private val authApiCache = ConcurrentHashMap<String, AuthApi>()

    /** Auth endpoints use the base client to prevent a 401 → refresh recursion. */
    private fun authApiFor(rawUrl: String): AuthApi {
        val base = normalizeBaseUrl(rawUrl)
        return authApiCache.getOrPut(base) {
            Retrofit.Builder()
                .baseUrl(base)
                .client(baseOkHttpClient)
                .addConverterFactory(GsonConverterFactory.create(gson))
                .build()
                .create(AuthApi::class.java)
        }
    }

    val authRepository: AuthRepository by lazy {
        AuthRepository(
            apiProvider = ::authApiFor,
            sessionStore = authSessionStore,
            baseUrlProvider = { BuildConfig.API_BASE_URL }
        )
    }

    private val accessTokenInterceptor by lazy { AccessTokenInterceptor(authRepository) }
    private val accessTokenAuthenticator by lazy { AccessTokenAuthenticator(authRepository) }

    // ============================================================
    // OkHttp — shared connection pool via newBuilder()
    // ============================================================

    /** Base OkHttp client with shared config (connection pool, interceptors) */
    private val baseOkHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .addInterceptor(HttpLoggingInterceptor().apply {
                level = HttpLoggingInterceptor.Level.HEADERS
                redactHeader("Authorization")
            })
            .build()
    }

    /** OkHttp client for Retrofit — 30s timeouts, body logging OK for short requests */
    val apiOkHttpClient: OkHttpClient by lazy {
        baseOkHttpClient.newBuilder()
            .addInterceptor(accessTokenInterceptor)
            .authenticator(accessTokenAuthenticator)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    /**
     * OkHttp client for SSE — bounded read timeout kept alive by the server heartbeat,
     * headers-only logging. The backend sends a ": ping" SSE comment line every 25s
     * during idle phases (model thinking, tool execution). Comment lines are dropped
     * by okhttp-sse before reaching onEvent, but each arrival completes a socket read
     * and grants a fresh full window — so a bounded readTimeout never kills a healthy
     * stream, while a silently dead connection (NAT reaping, network switch) raises
     * SocketTimeoutException after 90s (~3 heartbeats) → onFailure → retryable error
     * → 继续生成 entry, instead of hanging forever with readTimeout=0.
     */
    val sseOkHttpClient: OkHttpClient by lazy {
        baseOkHttpClient.newBuilder()
            .addInterceptor(accessTokenInterceptor)
            .authenticator(accessTokenAuthenticator)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS)   // ≈3× server heartbeat, see sse.HeartbeatInterval
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    // ============================================================
    // Network layer — endpoint fixed at build time (BuildConfig.API_BASE_URL)
    // ============================================================

    private data class NetworkLayer(val api: LabSageApi, val sse: SseDataSource)

    private val networkCache = ConcurrentHashMap<String, NetworkLayer>()

    /** Normalize a URL into a Retrofit-compatible base (trailing "/"). */
    private fun normalizeBaseUrl(raw: String): String {
        var u = raw.trim()
        if (u.isEmpty()) u = BuildConfig.API_BASE_URL
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

    /**
     * A non-refreshing client pinned to one authenticated session. Legacy import
     * must never follow a concurrent backend/account switch through dynamic DI.
     */
    fun legacyImportApiFor(session: AuthSession): LabSageApi {
        val base = normalizeBaseUrl(
            session.serverBaseUrl ?: error("Authenticated session has no server binding")
        )
        val fixedClient = baseOkHttpClient.newBuilder()
            .addInterceptor { chain ->
                chain.proceed(
                    chain.request().newBuilder()
                        .header("Authorization", "Bearer ${session.tokens.accessToken}")
                        .build()
                )
            }
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
        return Retrofit.Builder()
            .baseUrl(base)
            .client(fixedClient)
            .addConverterFactory(GsonConverterFactory.create(gson))
            .build()
            .create(LabSageApi::class.java)
    }

    /** Current [SseDataSource] for the active base URL. */
    val sseDataSource: SseDataSource get() = networkLayerFor(currentBaseUrl).sse

    // ============================================================
    // Room database
    // ============================================================

    val database: AppDatabase by lazy {
        Room.databaseBuilder(this, AppDatabase::class.java, "labsage.db")
            .addMigrations(AppDatabase.MIGRATION_1_2)
            .addMigrations(AppDatabase.MIGRATION_2_3)
            .addMigrations(AppDatabase.MIGRATION_3_4)
            .addMigrations(AppDatabase.MIGRATION_4_5)
            .addMigrations(AppDatabase.MIGRATION_5_6)
            .build()
    }

    // ============================================================
    // Repository
    // ============================================================

    val repository: ChatRepository by lazy {
        ChatRepository(
            app = this,
            database = database,
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

    val authViewModelFactory: ViewModelProvider.Factory by lazy {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                if (modelClass.isAssignableFrom(AuthViewModel::class.java)) {
                    return AuthViewModel(authRepository) as T
                }
                throw IllegalArgumentException("Unknown ViewModel: ${modelClass.name}")
            }
        }
    }

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

    val fileManagementViewModelFactory: ViewModelProvider.Factory by lazy {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                if (modelClass.isAssignableFrom(FileManagementViewModel::class.java)) {
                    return FileManagementViewModel(repository) as T
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
