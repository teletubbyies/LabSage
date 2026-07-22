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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.labsage.app.LabSageApplication
import com.labsage.app.R
import com.labsage.app.data.local.entity.ConversationEntity
import com.labsage.app.ui.chat.components.BrandMark
import com.labsage.app.ui.chat.components.SyncStatusBanner
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationListScreen(
    onConversationClick: (String) -> Unit,
    onNewConversation: (String) -> Unit,
    onOpenSettings: () -> Unit = {}
) {
    val context = LocalContext.current
    val app = context.applicationContext as LabSageApplication
    val viewModel: ConversationListViewModel = viewModel(factory = app.conversationListViewModelFactory)
    val conversations by viewModel.conversations.collectAsStateWithLifecycle()
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    val syncState by viewModel.syncState.collectAsStateWithLifecycle()

    val scheme = MaterialTheme.colorScheme
    val isSearching = searchQuery.isNotBlank()

    // Group the ordered list into pinned + date buckets (flat when searching).
    val groups = remember(conversations, isSearching) {
        groupConversations(conversations, isSearching)
    }

    // Rename dialog target.
    var renaming by remember { mutableStateOf<ConversationEntity?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()
    val unnamedConversation = stringResource(R.string.empty_conversation_title)

    Scaffold(
        containerColor = scheme.background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        BrandMark(size = 32.dp)
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text(
                                stringResource(R.string.app_name),
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                color = scheme.onBackground
                            )
                            Text(
                                stringResource(R.string.app_subtitle),
                                style = MaterialTheme.typography.labelSmall,
                                color = scheme.onSurfaceVariant
                            )
                        }
                    }
                },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, stringResource(R.string.settings), tint = scheme.onSurfaceVariant)
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
                icon = { Icon(Icons.Default.Edit, stringResource(R.string.new_conversation)) },
                text = { Text(stringResource(R.string.new_conversation), fontWeight = FontWeight.SemiBold) }
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            SyncStatusBanner(syncState, viewModel::refreshConversations)
            // Search bar — filled, rounded, always visible.
            OutlinedTextField(
                value = searchQuery,
                onValueChange = viewModel::setSearchQuery,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                placeholder = { Text(stringResource(R.string.search_conversations), color = scheme.onSurfaceVariant) },
                leadingIcon = { Icon(Icons.Default.Search, null, tint = scheme.onSurfaceVariant) },
                trailingIcon = {
                    AnimatedVisibility(visible = isSearching, enter = fadeIn(), exit = fadeOut()) {
                        IconButton(onClick = { viewModel.setSearchQuery("") }) {
                            Icon(Icons.Default.Close, stringResource(R.string.clear_search), tint = scheme.onSurfaceVariant)
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
                            Text(stringResource(R.string.no_search_results), color = scheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
                else -> LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 88.dp)
                ) {
                    groups.forEach { group ->
                        if (group.titleRes != null) {
                            item(key = "header_${group.titleRes}") { GroupHeader(group.titleRes) }
                        }
                        items(group.items, key = { "${group.titleRes}_${it.id}" }) { conv ->
                            ConversationItem(
                                conv,
                                onClick = { onConversationClick(conv.id) },
                                onTogglePin = { viewModel.togglePin(conv) },
                                onRename = { renaming = conv },
                                onDelete = {
                                    val displayTitle = conv.title.ifBlank { unnamedConversation }
                                    viewModel.stageDeleteConversation(conv.id)
                                    coroutineScope.launch {
                                        val result = snackbarHostState.showSnackbar(
                                            message = context.getString(R.string.conversation_deleted, displayTitle),
                                            actionLabel = context.getString(R.string.action_undo),
                                            duration = SnackbarDuration.Long
                                        )
                                        if (result == SnackbarResult.ActionPerformed) {
                                            viewModel.undoDeleteConversation(conv.id)
                                        } else {
                                            viewModel.commitDeleteConversation(conv.id)
                                        }
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    // Rename dialog.
    renaming?.let { conv ->
        var text by remember(conv.id) { mutableStateOf(conv.title.ifBlank { unnamedConversation }) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            icon = { Icon(Icons.Default.Edit, null, tint = scheme.primary) },
            title = { Text(stringResource(R.string.rename_conversation), fontWeight = FontWeight.SemiBold) },
            text = {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text(stringResource(R.string.conversation_title)) },
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
                ) { Text(stringResource(R.string.action_save)) }
            },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text(stringResource(R.string.action_cancel)) } }
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
                stringResource(R.string.empty_conversation_heading),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = scheme.onBackground
            )
            Spacer(Modifier.height(6.dp))
            Text(
                stringResource(R.string.empty_conversation_description),
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
                Text(stringResource(R.string.start_new_conversation), fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun GroupHeader(titleRes: Int) {
    val scheme = MaterialTheme.colorScheme
    val icon = if (titleRes == R.string.group_pinned) Icons.Default.PushPin else null
    Row(
        Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, top = 14.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            Icon(icon, null, Modifier.size(13.dp), tint = scheme.primary)
            Spacer(Modifier.width(5.dp))
        }
        Text(
            stringResource(titleRes),
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
                        conv.title.ifBlank { stringResource(R.string.empty_conversation_title) },
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
                    Icon(Icons.Default.MoreVert, stringResource(R.string.action_more), Modifier.size(18.dp), tint = scheme.onSurfaceVariant)
                }
                DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(if (conv.isPinned) R.string.unpin_conversation else R.string.pin_conversation)) },
                        leadingIcon = { Icon(Icons.Default.PushPin, null) },
                        onClick = { showMenu = false; onTogglePin() }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.action_rename)) },
                        leadingIcon = { Icon(Icons.Default.Edit, null) },
                        onClick = { showMenu = false; onRename() }
                    )
                    HorizontalDivider()
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.action_delete), color = scheme.error) },
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

private data class ConversationGroup(val titleRes: Int?, val items: List<ConversationEntity>)

private fun groupConversations(
    items: List<ConversationEntity>,
    isSearching: Boolean
): List<ConversationGroup> {
    if (items.isEmpty()) return emptyList()
    // While searching, return a single headerless group (search results).
    if (isSearching) return listOf(ConversationGroup(null, items))

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
    if (pinned.isNotEmpty()) groups += ConversationGroup(R.string.group_pinned, pinned)
    if (today.isNotEmpty()) groups += ConversationGroup(R.string.group_today, today)
    if (yesterday.isNotEmpty()) groups += ConversationGroup(R.string.group_yesterday, yesterday)
    if (earlier.isNotEmpty()) groups += ConversationGroup(R.string.group_earlier, earlier)
    return groups
}
