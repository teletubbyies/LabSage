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
        setIntent(intent)
        notificationDestination = intent.notificationDestination()
    }
}

object NavRoutes {
    const val CONVERSATION_LIST = "conversation_list"
    const val CHAT = "chat/{conversationId}?messageId={messageId}"
    const val SETTINGS = "settings"

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
            popUpTo(NavRoutes.CONVERSATION_LIST) { inclusive = false }
            launchSingleTop = true
        }
        onNotificationDestinationConsumed()
    }

    NavHost(
        navController = navController, // control navigation
        startDestination = NavRoutes.CONVERSATION_LIST // 1st displayed view
    ) {
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
                }
            )
        }

        composable(
            route = NavRoutes.CHAT,
            arguments = listOf(
                navArgument("conversationId") { type = NavType.StringType },
                navArgument("messageId") {
                    type = NavType.StringType
                    defaultValue = ""
                }
            )
        ) { backStackEntry ->
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

        composable(NavRoutes.SETTINGS) {
            SettingsScreen(
                username = authenticatedUsername,
                onBack = { navController.popBackStack() },
                onLogout = { coroutineScope.launch { app.authRepository.logout() } },
                // Application scope survives the authentication route disappearing after logout.
                onBaseUrlChange = app.backendSwitchCoordinator::switchTo
            )
        }
    }
}
