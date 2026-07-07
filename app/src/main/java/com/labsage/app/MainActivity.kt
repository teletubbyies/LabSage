package com.labsage.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.labsage.app.domain.model.ThemeMode
import com.labsage.app.ui.chat.ChatScreen
import com.labsage.app.ui.conversation.ConversationListScreen
import com.labsage.app.ui.settings.SettingsScreen
import com.labsage.app.ui.theme.LabSageTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            LabSageRoot()
        }
    }
}

object NavRoutes {
    const val CONVERSATION_LIST = "conversation_list"
    const val CHAT = "chat/{conversationId}"
    const val SETTINGS = "settings"

    fun chatRoute(conversationId: String) = "chat/$conversationId"
}

/**
 * Top-level composable: resolves the active theme from persisted settings
 * (system / light / dark) before hosting the nav graph, so a theme change in
 * Settings recomposes the whole tree.
 */
@Composable
fun LabSageRoot() {
    val app = LocalContext.current.applicationContext as LabSageApplication
    val settings by app.appSettings.collectAsStateWithLifecycle()
    val systemDark = isSystemInDarkTheme()
    val darkTheme = when (settings.themeMode) {
        ThemeMode.SYSTEM -> systemDark
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    LabSageTheme(darkTheme = darkTheme) {
        LabSageNavGraph()
    }
}

@Composable
fun LabSageNavGraph() {
    val navController = rememberNavController()

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
                navArgument("conversationId") { type = NavType.StringType }
            )
        ) { backStackEntry ->
            val conversationId = backStackEntry.arguments?.getString("conversationId") ?: return@composable
            ChatScreen(
                conversationId = conversationId,
                onBack = { navController.popBackStack() }
            )
        }

        composable(NavRoutes.SETTINGS) {
            SettingsScreen(onBack = { navController.popBackStack() })
        }
    }
}
