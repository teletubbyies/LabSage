package com.labsage.app.ui.conversation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.labsage.app.LabSageApplication
import com.labsage.app.data.local.entity.ConversationEntity
import com.labsage.app.ui.chat.components.BrandMark
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
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        BrandMark(size = 32.dp)
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text(
                                "LabSage",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                color = scheme.onBackground
                            )
                            Text(
                                "实验室智能助手",
                                style = MaterialTheme.typography.labelSmall,
                                color = scheme.onSurfaceVariant
                            )
                        }
                    }
                },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, "设置", tint = scheme.onSurfaceVariant)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = scheme.surface)
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { onNewConversation(viewModel.createNewConversation()) },
                containerColor = scheme.primary,
                contentColor = scheme.onPrimary,
                shape = RoundedCornerShape(18.dp),
                icon = { Icon(Icons.Default.Edit, "新会话") },
                text = { Text("新会话", fontWeight = FontWeight.SemiBold) }
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            // Search bar — filled, rounded, always visible.
            OutlinedTextField(
                value = searchQuery,
                onValueChange = viewModel::setSearchQuery,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                placeholder = { Text("搜索会话标题", color = scheme.onSurfaceVariant) },
                leadingIcon = { Icon(Icons.Default.Search, null, tint = scheme.onSurfaceVariant) },
                trailingIcon = {
                    AnimatedVisibility(visible = isSearching, enter = fadeIn(), exit = fadeOut()) {
                        IconButton(onClick = { viewModel.setSearchQuery("") }) {
                            Icon(Icons.Default.Close, "清除", tint = scheme.onSurfaceVariant)
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = androidx.compose.ui.graphics.Color.Transparent,
                    unfocusedBorderColor = androidx.compose.ui.graphics.Color.Transparent,
                    focusedContainerColor = scheme.surfaceVariant,
                    unfocusedContainerColor = scheme.surfaceVariant,
                    cursorColor = scheme.primary
                )
            )

            when {
                conversations.isEmpty() && !isSearching -> EmptyState { onNewConversation(viewModel.createNewConversation()) }
                conversations.isEmpty() && isSearching -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Default.SearchOff, null, tint = scheme.onSurfaceVariant, modifier = Modifier.size(40.dp))
                            Spacer(Modifier.height(10.dp))
                            Text("未找到匹配的会话", color = scheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
                else -> LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 88.dp)
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
            icon = { Icon(Icons.Default.Edit, null, tint = scheme.primary) },
            title = { Text("重命名会话", fontWeight = FontWeight.SemiBold) },
            text = {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("标题") },
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = scheme.primary,
                        cursorColor = scheme.primary
                    )
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.rename(conv.id, text)
                        renaming = null
                    },
                    enabled = text.isNotBlank(),
                    shape = RoundedCornerShape(12.dp),
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
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            BrandMark(size = 72.dp)
            Spacer(Modifier.height(20.dp))
            Text(
                "开启你的第一次对话",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = scheme.onBackground
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "向 LabSage 提问 GPU、Conda 环境或任何实验问题",
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
            Spacer(Modifier.height(22.dp))
            Button(
                onClick = onStart,
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = scheme.primary)
            ) {
                Icon(Icons.Default.Add, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("开始新会话", fontWeight = FontWeight.SemiBold)
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
        Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, top = 14.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            Icon(icon, null, Modifier.size(13.dp), tint = scheme.primary)
            Spacer(Modifier.width(5.dp))
        }
        Text(
            label,
            style = MaterialTheme.typography.labelLarge.copy(
                fontWeight = FontWeight.SemiBold,
                color = scheme.onSurfaceVariant,
                letterSpacing = 0.8.sp
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
            .padding(vertical = 4.dp)
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        color = if (conv.isPinned) scheme.primaryContainer.copy(alpha = 0.45f) else scheme.surface,
        shadowElevation = if (conv.isPinned) 0.dp else 0.5.dp,
        tonalElevation = 0.dp
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Leading avatar
            Surface(
                shape = RoundedCornerShape(13.dp),
                color = if (conv.isPinned) scheme.primaryContainer else scheme.surfaceVariant,
                modifier = Modifier.size(44.dp)
            ) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.AutoMirrored.Filled.Chat,
                        null,
                        Modifier.size(20.dp),
                        tint = scheme.primary
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (conv.isPinned) {
                        Icon(Icons.Default.PushPin, null, Modifier.size(13.dp), tint = scheme.primary)
                        Spacer(Modifier.width(5.dp))
                    }
                    Text(
                        conv.title,
                        style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium, fontSize = 15.sp),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = scheme.onSurface,
                        modifier = Modifier.weight(1f)
                    )
                }
                Spacer(Modifier.height(3.dp))
                Text(
                    fmt.format(Date(conv.updatedAt)),
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.onSurfaceVariant
                )
            }
            // Overflow menu
            Box {
                IconButton(onClick = { showMenu = true }, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.MoreVert, "更多", Modifier.size(18.dp), tint = scheme.onSurfaceVariant)
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
