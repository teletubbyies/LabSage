package com.labsage.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.labsage.app.ui.chat.ChatScreen
import com.labsage.app.ui.conversation.ConversationListScreen
import com.labsage.app.ui.theme.LabSageTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            LabSageTheme {
                LabSageNavGraph()
            }
        }
    }
}

object NavRoutes {
    const val CONVERSATION_LIST = "conversation_list"
    const val CHAT = "chat/{conversationId}"

    fun chatRoute(conversationId: String) = "chat/$conversationId"
}

@Composable
fun LabSageNavGraph() {
    val navController = rememberNavController()

    NavHost(
        navController = navController,
        startDestination = NavRoutes.CONVERSATION_LIST
    ) {
        composable(NavRoutes.CONVERSATION_LIST) {
            ConversationListScreen(
                onConversationClick = { id ->
                    navController.navigate(NavRoutes.chatRoute(id))
                },
                onNewConversation = { id ->
                    navController.navigate(NavRoutes.chatRoute(id))
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
    }
}
