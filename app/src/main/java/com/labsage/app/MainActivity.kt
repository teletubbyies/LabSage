package com.labsage.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.labsage.app.data.auth.AuthenticationState
import com.labsage.app.ui.auth.AuthLoadingScreen
import com.labsage.app.ui.auth.AuthScreen
import com.labsage.app.domain.model.ThemeMode
import com.labsage.app.notification.ResponseNotificationManager
import com.labsage.app.ui.chat.ChatScreen
import com.labsage.app.ui.conversation.ConversationListScreen
import com.labsage.app.ui.files.FileManagementScreen
import com.labsage.app.ui.settings.SettingsScreen
import com.labsage.app.ui.theme.LabSageTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private var notificationDestination by mutableStateOf<NotificationDestination?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        notificationDestination = intent.notificationDestination()
        enableEdgeToEdge()
        setContent {
            LabSageRoot(
                notificationDestination = notificationDestination,
                onNotificationDestinationConsumed = { notificationDestination = null }
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)   // 防止后续再获取intent时拿到旧数据
        notificationDestination = intent.notificationDestination()
    }
}

object NavRoutes {
    const val CONVERSATION_LIST = "conversation_list"
    const val CHAT = "chat/{conversationId}?messageId={messageId}"
    const val SETTINGS = "settings"
    const val FILES = "files"

    fun chatRoute(conversationId: String, messageId: String? = null): String = buildString {
        append("chat/")
        append(Uri.encode(conversationId))
        messageId?.let { append("?messageId=${Uri.encode(it)}") }
    }
}

data class NotificationDestination(
    val conversationId: String,
    val messageId: String
)

internal fun Intent?.notificationDestination(): NotificationDestination? {
    if (this?.action != ResponseNotificationManager.ACTION_OPEN_RESPONSE) return null
    val conversationId = getStringExtra(ResponseNotificationManager.EXTRA_CONVERSATION_ID)
        ?.takeIf { it.isNotBlank() }
        ?: return null
    val messageId = getStringExtra(ResponseNotificationManager.EXTRA_MESSAGE_ID)
        ?.takeIf { it.isNotBlank() }
        ?: return null
    return NotificationDestination(conversationId, messageId)
}

/**
 * Top-level composable: resolves the active theme from persisted settings
 * (system / light / dark) before hosting the nav graph, so a theme change in
 * Settings recomposes the whole tree.
 */
@Composable
fun LabSageRoot(
    notificationDestination: NotificationDestination? = null,
    onNotificationDestinationConsumed: () -> Unit = {}
) {
    val app = LocalContext.current.applicationContext as LabSageApplication
    val settings by app.appSettings.collectAsStateWithLifecycle()
    val authenticationState by app.authRepository.state.collectAsStateWithLifecycle()
    val systemDark = isSystemInDarkTheme()
    val darkTheme = when (settings.themeMode) {
        ThemeMode.SYSTEM -> systemDark
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    LabSageTheme(darkTheme = darkTheme) {
        when (val authState = authenticationState) {
            AuthenticationState.Initializing -> AuthLoadingScreen()
            AuthenticationState.Unauthenticated -> AuthScreen()
            is AuthenticationState.Authenticated -> LabSageNavGraph(
                authenticatedUsername = authState.user.username,
                notificationDestination = notificationDestination,
                onNotificationDestinationConsumed = onNotificationDestinationConsumed
            )
        }
    }
}

@Composable
fun LabSageNavGraph(
    authenticatedUsername: String,
    notificationDestination: NotificationDestination? = null,
    onNotificationDestinationConsumed: () -> Unit = {}
) {
    val navController = rememberNavController()
    val app = LocalContext.current.applicationContext as LabSageApplication
    val coroutineScope = rememberCoroutineScope()

    LaunchedEffect(notificationDestination) {
        val destination = notificationDestination ?: return@LaunchedEffect
        navController.navigate(
            NavRoutes.chatRoute(destination.conversationId, destination.messageId)
        ) {
            //清除对话列表上的页面，保留对话列表本身
            //这样用户从通知打开聊天后，按返回会回到对话列表
            //inclusive=false表示不删除对话列表本身
            popUpTo(NavRoutes.CONVERSATION_LIST) { inclusive = false }
            launchSingleTop = true // 单栈顶模式
        }
        onNotificationDestinationConsumed()
    }

    NavHost(
        navController = navController, // control navigation
        startDestination = NavRoutes.CONVERSATION_LIST // 1st displayed view
    ) {
        // 会话列表页面
        composable(NavRoutes.CONVERSATION_LIST) {
            ConversationListScreen(
                onConversationClick = { id ->
                    navController.navigate(NavRoutes.chatRoute(id))
                },
                onNewConversation = { id ->
                    navController.navigate(NavRoutes.chatRoute(id))
                },
                onOpenSettings = {
                    navController.navigate(NavRoutes.SETTINGS)
                },
                onOpenFiles = {
                    navController.navigate(NavRoutes.FILES)
                }
            )
        }

        // 对话页面
        composable(
            route = NavRoutes.CHAT,
            arguments = listOf(
                navArgument("conversationId") { type = NavType.StringType },
                navArgument("messageId") {
                    type = NavType.StringType
                    defaultValue = ""
                }
            )
        ) {
            // 包含当前页面的路由、路由参数、当前页面的生命周期、与该页面绑定的状态和 ViewModel 信息
            backStackEntry ->
            // 读取返回栈的路由参数
            // return@composable只退出 composable 的页面内容，不是退出整个 LabSageNavGraph()
            val conversationId = backStackEntry.arguments?.getString("conversationId") ?: return@composable
            val targetMessageId = backStackEntry.arguments
                ?.getString("messageId")
                ?.takeIf { it.isNotBlank() }
            ChatScreen(
                conversationId = conversationId,
                targetMessageId = targetMessageId,
                onBack = { navController.popBackStack() }
            )
        }

        // 设置页面
        composable(NavRoutes.SETTINGS) {
            SettingsScreen(
                username = authenticatedUsername,
                onBack = { navController.popBackStack() },
                // Application scope survives the authentication route disappearing after logout.
                onLogout = { coroutineScope.launch { app.authRepository.logout() } }
            )
        }

        // 文件管理页面
        composable(NavRoutes.FILES) {
            FileManagementScreen(onBack = { navController.popBackStack() })
        }
    }
}
