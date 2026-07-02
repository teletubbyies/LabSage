package com.labsage.app.ui.conversation

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.labsage.app.LabSageApplication
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationListScreen(
    onConversationClick: (String) -> Unit,
    onNewConversation: (String) -> Unit
) {
    val app = androidx.compose.ui.platform.LocalContext.current.applicationContext as LabSageApplication
    val viewModel: ConversationListViewModel = viewModel(factory = app.conversationListViewModelFactory)
    val conversations by viewModel.conversations.collectAsStateWithLifecycle()

    val scheme = MaterialTheme.colorScheme

    Scaffold(
        containerColor = scheme.background,
        topBar = {
            TopAppBar(
                title = { Text("LabSage", fontWeight = FontWeight.Bold, color = scheme.onBackground) },
                actions = {
                    IconButton(onClick = { onNewConversation(viewModel.createNewConversation()) }) {
                        Icon(Icons.Default.Add, "New chat", tint = scheme.onSurfaceVariant)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = scheme.surface)
            )
        }
    ) { padding ->
        if (conversations.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Surface(shape = RoundedCornerShape(20.dp), color = scheme.primaryContainer, modifier = Modifier.size(64.dp)) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Icon(Icons.AutoMirrored.Filled.Chat, null, tint = scheme.primary, modifier = Modifier.size(28.dp))
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                    Text("No conversations yet", style = MaterialTheme.typography.titleMedium, color = scheme.onSurfaceVariant)
                    Spacer(Modifier.height(4.dp))
                    Text("Tap + to start a new chat", style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                    Spacer(Modifier.height(20.dp))
                    Button(
                        onClick = { onNewConversation(viewModel.createNewConversation()) },
                        colors = ButtonDefaults.buttonColors(containerColor = scheme.primary),
                        shape = RoundedCornerShape(12.dp)
                    ) { Text("Start Chat") }
                }
            }
        } else {
            LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(vertical = 4.dp)) {
                item {
                    TextButton(
                        onClick = { onNewConversation(viewModel.createNewConversation()) },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)
                    ) {
                        Icon(Icons.Default.Add, null, Modifier.size(18.dp), tint = scheme.primary)
                        Spacer(Modifier.width(8.dp))
                        Text("New conversation", color = scheme.primary, fontWeight = FontWeight.Medium)
                    }
                }
                items(conversations, key = { it.id }) { conv ->
                    ConversationItem(conv, { onConversationClick(conv.id) }, { viewModel.deleteConversation(conv.id) })
                }
            }
        }
    }
}

@Composable
private fun ConversationItem(
    conv: com.labsage.app.data.local.entity.ConversationEntity,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    val fmt = remember { SimpleDateFormat("MM/dd HH:mm", Locale.getDefault()) }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 3.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        color = scheme.surface,
        shadowElevation = 0.5.dp
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(shape = RoundedCornerShape(12.dp), color = scheme.primaryContainer, modifier = Modifier.size(44.dp)) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Icon(Icons.AutoMirrored.Filled.Chat, null, Modifier.size(20.dp), tint = scheme.primary)
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(conv.title, style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium, fontSize = 15.sp), maxLines = 1, overflow = TextOverflow.Ellipsis, color = scheme.onBackground)
                Spacer(Modifier.height(2.dp))
                Text(fmt.format(Date(conv.updatedAt)), style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
            }
            IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Default.Delete, "Delete", Modifier.size(16.dp), tint = scheme.onSurfaceVariant)
            }
        }
    }
}
