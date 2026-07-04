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
import com.labsage.app.data.local.entity.ConversationEntity
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationListScreen(
    onConversationClick: (String) -> Unit,
    onNewConversation: (String) -> Unit,
    onOpenSettings: () -> Unit = {}
) {
    val app = androidx.compose.ui.platform.LocalContext.current.applicationContext as LabSageApplication
    val viewModel: ConversationListViewModel = viewModel(factory = app.conversationListViewModelFactory)
    val conversations by viewModel.conversations.collectAsStateWithLifecycle()
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()

    val scheme = MaterialTheme.colorScheme
    val isSearching = searchQuery.isNotBlank()

    // Group the ordered list into pinned + date buckets (flat when searching).
    val groups = remember(conversations, isSearching) {
        groupConversations(conversations, isSearching)
    }

    // Rename dialog target.
    var renaming by remember { mutableStateOf<ConversationEntity?>(null) }

    Scaffold(
        containerColor = scheme.background,
        topBar = {
            TopAppBar(
                title = { Text("LabSage", fontWeight = FontWeight.Bold, color = scheme.onBackground) },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, "设置", tint = scheme.onSurfaceVariant)
                    }
                    IconButton(onClick = { onNewConversation(viewModel.createNewConversation()) }) {
                        Icon(Icons.Default.Add, "New chat", tint = scheme.onSurfaceVariant)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = scheme.surface)
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            // Search bar — always visible, filters by title.
            OutlinedTextField(
                value = searchQuery,
                onValueChange = viewModel::setSearchQuery,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                placeholder = { Text("搜索会话标题", color = scheme.onSurfaceVariant) },
                leadingIcon = { Icon(Icons.Default.Search, null, tint = scheme.onSurfaceVariant) },
                trailingIcon = {
                    if (isSearching) {
                        IconButton(onClick = { viewModel.setSearchQuery("") }) {
                            Icon(Icons.Default.Close, "清除", tint = scheme.onSurfaceVariant)
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = scheme.primary,
                    unfocusedBorderColor = scheme.outlineVariant
                )
            )

            when {
                conversations.isEmpty() && !isSearching -> EmptyState { onNewConversation(viewModel.createNewConversation()) }
                conversations.isEmpty() && isSearching -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("未找到匹配的会话", color = scheme.onSurfaceVariant)
                    }
                }
                else -> LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(vertical = 4.dp)
                ) {
                    groups.forEach { group ->
                        if (group.title.isNotEmpty()) {
                            item(key = "header_${group.title}") { GroupHeader(group.title) }
                        }
                        items(group.items, key = { "${group.title}_${it.id}" }) { conv ->
                            ConversationItem(
                                conv,
                                onClick = { onConversationClick(conv.id) },
                                onTogglePin = { viewModel.togglePin(conv) },
                                onRename = { renaming = conv },
                                onDelete = { viewModel.deleteConversation(conv.id) }
                            )
                        }
                    }
                }
            }
        }
    }

    // Rename dialog.
    renaming?.let { conv ->
        var text by remember(conv.id) { mutableStateOf(conv.title) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text("重命名会话", fontWeight = FontWeight.SemiBold) },
            text = {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("标题") },
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = scheme.primary)
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.rename(conv.id, text)
                        renaming = null
                    },
                    enabled = text.isNotBlank(),
                    colors = ButtonDefaults.buttonColors(containerColor = scheme.primary)
                ) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text("取消") } }
        )
    }
}

@Composable
private fun EmptyState(onStart: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
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
            Button(onClick = onStart, colors = ButtonDefaults.buttonColors(containerColor = scheme.primary), shape = RoundedCornerShape(12.dp)) {
                Text("Start Chat")
            }
        }
    }
}

@Composable
private fun GroupHeader(title: String) {
    val scheme = MaterialTheme.colorScheme
    val (label, icon) = when (title) {
        "置顶" -> "置顶" to Icons.Default.PushPin
        else -> title to null
    }
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            Icon(icon, null, Modifier.size(14.dp), tint = scheme.primary)
            Spacer(Modifier.width(4.dp))
        }
        Text(
            label,
            style = MaterialTheme.typography.labelMedium.copy(
                fontWeight = FontWeight.SemiBold,
                color = scheme.onSurfaceVariant
            )
        )
    }
}

@Composable
private fun ConversationItem(
    conv: ConversationEntity,
    onClick: () -> Unit,
    onTogglePin: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    val fmt = remember { SimpleDateFormat("MM/dd HH:mm", Locale.getDefault()) }
    var showMenu by remember { mutableStateOf(false) }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 3.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        color = if (conv.isPinned) scheme.primaryContainer.copy(alpha = 0.4f) else scheme.surface,
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
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (conv.isPinned) {
                        Icon(Icons.Default.PushPin, null, Modifier.size(13.dp), tint = scheme.primary)
                        Spacer(Modifier.width(4.dp))
                    }
                    Text(
                        conv.title,
                        style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium, fontSize = 15.sp),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = scheme.onBackground,
                        modifier = Modifier.weight(1f)
                    )
                }
                Spacer(Modifier.height(2.dp))
                Text(fmt.format(Date(conv.updatedAt)), style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
            }
            // Overflow menu
            Box {
                IconButton(onClick = { showMenu = true }, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.MoreVert, "更多", Modifier.size(16.dp), tint = scheme.onSurfaceVariant)
                }
                DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                    DropdownMenuItem(
                        text = { Text(if (conv.isPinned) "取消置顶" else "置顶") },
                        leadingIcon = { Icon(Icons.Default.PushPin, null) },
                        onClick = { showMenu = false; onTogglePin() }
                    )
                    DropdownMenuItem(
                        text = { Text("重命名") },
                        leadingIcon = { Icon(Icons.Default.Edit, null) },
                        onClick = { showMenu = false; onRename() }
                    )
                    HorizontalDivider()
                    DropdownMenuItem(
                        text = { Text("删除", color = scheme.error) },
                        leadingIcon = { Icon(Icons.Default.Delete, null, tint = scheme.error) },
                        onClick = { showMenu = false; onDelete() }
                    )
                }
            }
        }
    }
}

// ============================================================
// Grouping — pinned first, then today / yesterday / earlier.
// ============================================================

private data class ConversationGroup(val title: String, val items: List<ConversationEntity>)

private fun groupConversations(
    items: List<ConversationEntity>,
    isSearching: Boolean
): List<ConversationGroup> {
    if (items.isEmpty()) return emptyList()
    // While searching, return a single headerless group (search results).
    if (isSearching) return listOf(ConversationGroup("", items))

    val pinned = items.filter { it.isPinned }
    val unpinned = items.filterNot { it.isPinned }

    val cal = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }
    val startToday = cal.timeInMillis
    val dayMs = 24L * 60 * 60 * 1000

    val today = unpinned.filter { it.updatedAt >= startToday }
    val yesterday = unpinned.filter { it.updatedAt in (startToday - dayMs) until startToday }
    val earlier = unpinned.filter { it.updatedAt < startToday - dayMs }

    val groups = mutableListOf<ConversationGroup>()
    if (pinned.isNotEmpty()) groups += ConversationGroup("置顶", pinned)
    if (today.isNotEmpty()) groups += ConversationGroup("今天", today)
    if (yesterday.isNotEmpty()) groups += ConversationGroup("昨天", yesterday)
    if (earlier.isNotEmpty()) groups += ConversationGroup("更早", earlier)
    return groups
}
