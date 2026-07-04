package com.labsage.app.ui.chat

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.*
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.labsage.app.LabSageApplication
import com.labsage.app.domain.model.QuickAction
import com.labsage.app.domain.model.StreamState
import com.labsage.app.ui.chat.components.*
import com.labsage.app.ui.theme.LocalExtendedColors
import dev.jeziellago.compose.markdowntext.MarkdownText

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ChatScreen(conversationId: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as LabSageApplication
    val viewModel: ChatViewModel = viewModel(key = conversationId, factory = app.chatViewModelFactory(conversationId))

    val messages by viewModel.messages.collectAsStateWithLifecycle()
    val streamState by viewModel.streamState.collectAsStateWithLifecycle()
    val traceLines by viewModel.traceLines.collectAsStateWithLifecycle()
    val justCompleted by viewModel.justCompleted.collectAsStateWithLifecycle()
    val sessionContext by viewModel.sessionContext.collectAsStateWithLifecycle()
    val toolCalls by viewModel.toolCalls.collectAsStateWithLifecycle()
    val activeRoute by viewModel.activeRoute.collectAsStateWithLifecycle()
    val pendingSend by viewModel.pendingSend.collectAsStateWithLifecycle()
    val settings by app.appSettings.collectAsStateWithLifecycle()

    // Build the quick-action chips from the user's configured default actions.
    val quickChips = remember(settings.quickActions) {
        settings.quickActions.mapNotNull { action ->
            when (action) {
                QuickAction.GPU -> QuickChip("GPU", Icons.Default.Memory) { viewModel.queryGpuStatus() }
                QuickAction.CONDA -> QuickChip("Conda", Icons.Default.Terminal) { viewModel.queryCondaEnv() }
            }
        }
    }

    var inputText by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    val isStreaming by remember { derivedStateOf { streamState is StreamState.Streaming } }

    val ext = LocalExtendedColors.current
    val scheme = MaterialTheme.colorScheme

    var isOnline by remember { mutableStateOf(true) }
    DisposableEffect(Unit) {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(n: Network) { isOnline = true }
            override fun onLost(n: Network) { isOnline = false }
        }
        cm.registerNetworkCallback(NetworkRequest.Builder().build(), cb)
        cm.activeNetwork?.let { cm.getNetworkCapabilities(it) }?.let {
            isOnline = it.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        }
        onDispose { cm.unregisterNetworkCallback(cb) }
    }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { viewModel.uploadFile(it, context) }
    }

    // Auto-scroll: animate on new messages, snap on content updates (faster)
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
    }
    // Snap scroll on streaming content updates — avoid per-chunk animation jank
    LaunchedEffect(messages.lastOrNull()?.content) {
        if (isStreaming && messages.isNotEmpty()) listState.scrollToItem(messages.size - 1)
    }

    var showTools by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = scheme.background,
        topBar = {
            TopAppBar(
                title = { Text("LabSage", fontWeight = FontWeight.SemiBold, color = scheme.onBackground) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = scheme.onBackground) } },
                actions = {
                    IconButton(onClick = { viewModel.loadSessionContext() }) {
                        Icon(Icons.Default.Description, "Summary", tint = scheme.onSurfaceVariant)
                    }
                    Box {
                        IconButton(onClick = { showTools = true }) {
                            Icon(Icons.Default.Build, "Tools", tint = scheme.onSurfaceVariant)
                        }
                        DropdownMenu(expanded = showTools, onDismissRequest = { showTools = false }, offset = DpOffset(0.dp, 0.dp)) {
                            DropdownMenuItem(text = { Text("Query GPU Status") }, leadingIcon = { Icon(Icons.Default.Memory, null) }, onClick = { showTools = false; viewModel.queryGpuStatus() })
                            DropdownMenuItem(text = { Text("Query Conda Env") }, leadingIcon = { Icon(Icons.Default.Terminal, null) }, onClick = { showTools = false; viewModel.queryCondaEnv() })
                        }
                    }
                    IconButton(onClick = { filePicker.launch("*/*") }) {
                        Icon(Icons.Default.AttachFile, "Upload", tint = scheme.onSurfaceVariant)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = scheme.surface)
            )
        },
        bottomBar = {
            ChatInputBar(value = inputText, onValueChange = { inputText = it },
                onSend = { if (inputText.isNotBlank()) { viewModel.sendMessage(inputText.trim()); inputText = "" } },
                enabled = !isStreaming, quickChips = quickChips,
                onVoiceResult = { recognized ->
                    // Append recognized speech to whatever is already typed.
                    inputText = if (inputText.isBlank()) recognized else inputText + recognized
                })
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            // Offline banner
            if (!isOnline) {
                Surface(Modifier.fillMaxWidth(), color = scheme.errorContainer) {
                    Row(Modifier.padding(horizontal = 14.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.CloudOff, null, Modifier.size(15.dp), tint = scheme.error)
                        Spacer(Modifier.width(8.dp))
                        Text("No network connection", style = MaterialTheme.typography.labelSmall, color = scheme.error)
                    }
                }
            }

            LazyColumn(Modifier.weight(1f), state = listState, contentPadding = PaddingValues(vertical = 8.dp)) {
                items(messages, key = { it.id }) { msg ->
                    var showMenu by remember { mutableStateOf(false) }
                    Box {
                        MessageBubble(msg.role, msg.content, msg.isPartial && isStreaming,
                            if (msg.isPartial && isStreaming && msg.role == "assistant") traceLines else emptyList(),
                            Modifier.combinedClickable(onClick = {}, onLongClick = { showMenu = true }))
                        DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                            DropdownMenuItem(text = { Text("Copy") }, leadingIcon = { Icon(Icons.Default.ContentCopy, null) }, onClick = {
                                showMenu = false
                                (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                                    .setPrimaryClip(ClipData.newPlainText("msg", msg.content))
                                Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
                            })
                            DropdownMenuItem(text = { Text("Share") }, leadingIcon = { Icon(Icons.Default.Share, null) }, onClick = {
                                showMenu = false
                                context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, msg.content) }, "Share"))
                            })
                            if (msg.role == "user") DropdownMenuItem(text = { Text("Re-edit") }, leadingIcon = { Icon(Icons.Default.Edit, null) }, onClick = { showMenu = false; inputText = msg.content })
                        }
                    }
                }
                if (isStreaming && messages.none { it.role == "assistant" && it.isPartial }) {
                    item { StreamingIndicator() }
                }
                if (toolCalls.isNotEmpty() || activeRoute != null) {
                    item(key = "tool_panel") {
                        ToolExecutionPanel(toolCalls = toolCalls, route = activeRoute)
                    }
                }
            }

            if (justCompleted) {
                androidx.compose.animation.AnimatedVisibility(
                    visible = justCompleted,
                    enter = androidx.compose.animation.fadeIn() + androidx.compose.animation.slideInVertically(),
                    exit = androidx.compose.animation.fadeOut()
                ) {
                    Surface(
                        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                        shape = RoundedCornerShape(10.dp), color = ext.completionBg
                    ) {
                        Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("✓", color = Color(0xFF34D399), style = MaterialTheme.typography.labelMedium)
                            Spacer(Modifier.width(6.dp))
                            Text("Response complete", style = MaterialTheme.typography.labelSmall, color = Color(0xFF34D399))
                        }
                    }
                }
            }

            when (val s = streamState) {
                is StreamState.Error -> if (s.retryable) PartialMessageBanner(s.message, { viewModel.continueGeneration() }, { viewModel.regenerate() })
                else Snackbar(Modifier.padding(16.dp)) { Text("Error: ${s.message}") }
                else -> {}
            }
        }
    }

    // Approval gate — visible only when approval mode stages a pending send.
    val pending = pendingSend
    if (pending != null) {
        AlertDialog(
            onDismissRequest = { viewModel.cancelPendingSend() },
            icon = { Icon(Icons.Default.Verified, null, tint = scheme.primary) },
            title = { Text("确认执行", fontWeight = FontWeight.SemiBold) },
            text = {
                Column {
                    Text("审批模式已开启。发送此消息可能触发后端工具调用：", style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(8.dp))
                    Surface(shape = MaterialTheme.shapes.small, color = scheme.background, modifier = Modifier.fillMaxWidth()) {
                        Text(pending, Modifier.padding(8.dp), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            },
            confirmButton = {
                Button(onClick = { viewModel.confirmPendingSend() }, colors = ButtonDefaults.buttonColors(containerColor = scheme.primary)) {
                    Text("允许发送")
                }
            },
            dismissButton = { TextButton(onClick = { viewModel.cancelPendingSend() }) { Text("取消") } }
        )
    }

    // Session context dialog
    val ctx = sessionContext
    if (ctx != null) {
        var editText by remember(ctx) { mutableStateOf(ctx.summary) }
        AlertDialog(
            onDismissRequest = { viewModel.dismissSessionContext() },
            icon = { Icon(Icons.Default.Description, null, tint = scheme.primary) },
            title = { Text("Session Summary", fontWeight = FontWeight.SemiBold) },
            text = {
                Column {
                    OutlinedTextField(
                        value = editText, onValueChange = { editText = it },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 130.dp),
                        label = { Text("Summary (Markdown)") },
                        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = scheme.primary)
                    )
                    Spacer(Modifier.height(8.dp))
                    Text("Preview", style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant)
                    Spacer(Modifier.height(4.dp))
                    Surface(
                        shape = MaterialTheme.shapes.small,
                        color = scheme.background,
                        modifier = Modifier.fillMaxWidth().heightIn(max = 200.dp)
                    ) {
                        if (editText.isNotBlank()) {
                            MarkdownText(
                                markdown = editText,
                                modifier = Modifier.padding(8.dp)
                            )
                        } else {
                            Text("Summary preview will appear here", Modifier.padding(8.dp), style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                        }
                    }
                    if (ctx.facts.isNotBlank()) {
                        Spacer(Modifier.height(12.dp))
                        Text("Facts · auto-generated", style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant)
                        Spacer(Modifier.height(4.dp))
                        Surface(shape = MaterialTheme.shapes.small, color = scheme.background) {
                            MarkdownText(markdown = ctx.facts.take(500), modifier = Modifier.padding(8.dp))
                        }
                    }
                }
            },
            confirmButton = { Button(onClick = { viewModel.updateSessionSummary(editText) }, colors = ButtonDefaults.buttonColors(containerColor = scheme.primary)) { Text("Save") } },
            dismissButton = { TextButton(onClick = { viewModel.dismissSessionContext() }) { Text("Close") } }
        )
    }
}
