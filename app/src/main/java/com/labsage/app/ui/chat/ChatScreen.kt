package com.labsage.app.ui.chat

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.*
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.labsage.app.LabSageApplication
import com.labsage.app.domain.model.StreamState
import com.labsage.app.ui.chat.components.*
import com.labsage.app.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ChatScreen(conversationId: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as LabSageApplication
    val viewModel: ChatViewModel = viewModel(key = conversationId, factory = app.chatViewModelFactory(conversationId))

    val messages by viewModel.messages.collectAsStateWithLifecycle()
    val streamState by viewModel.streamState.collectAsStateWithLifecycle()
    val traceLines by viewModel.traceLines.collectAsStateWithLifecycle()
    val sessionContext by viewModel.sessionContext.collectAsStateWithLifecycle()

    var inputText by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    val isStreaming = streamState is StreamState.Streaming

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

    LaunchedEffect(messages.size, messages.lastOrNull()?.content) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
    }

    var showTools by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = Background,
        topBar = {
            TopAppBar(
                title = { Text("LabSage", fontWeight = FontWeight.SemiBold, color = TextPrimary) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = TextPrimary) } },
                actions = {
                    IconButton(onClick = { viewModel.loadSessionContext() }) {
                        Icon(Icons.Default.Description, "Summary", tint = TextSecondary)
                    }
                    Box {
                        IconButton(onClick = { showTools = true }) {
                            Icon(Icons.Default.Build, "Tools", tint = TextSecondary)
                        }
                        DropdownMenu(expanded = showTools, onDismissRequest = { showTools = false }, offset = DpOffset(0.dp, 0.dp)) {
                            DropdownMenuItem(text = { Text("Query GPU Status") }, leadingIcon = { Icon(Icons.Default.Memory, null) }, onClick = { showTools = false; viewModel.queryGpuStatus() })
                            DropdownMenuItem(text = { Text("Query Conda Env") }, leadingIcon = { Icon(Icons.Default.Terminal, null) }, onClick = { showTools = false; viewModel.queryCondaEnv() })
                        }
                    }
                    IconButton(onClick = { filePicker.launch("*/*") }) {
                        Icon(Icons.Default.AttachFile, "Upload", tint = TextSecondary)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Surface)
            )
        },
        bottomBar = {
            ChatInputBar(value = inputText, onValueChange = { inputText = it },
                onSend = { if (inputText.isNotBlank()) { viewModel.sendMessage(inputText.trim()); inputText = "" } },
                enabled = !isStreaming, onGpuQuery = { viewModel.queryGpuStatus() }, onCondaQuery = { viewModel.queryCondaEnv() })
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            // Offline banner
            if (!isOnline) {
                Surface(Modifier.fillMaxWidth(), color = ErrorContainer) {
                    Row(Modifier.padding(horizontal = 14.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.CloudOff, null, Modifier.size(15.dp), tint = Error)
                        Spacer(Modifier.width(8.dp))
                        Text("No network connection", style = MaterialTheme.typography.labelSmall, color = Error)
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
            }

            when (val s = streamState) {
                is StreamState.Error -> if (s.retryable) PartialMessageBanner(s.message, { viewModel.continueGeneration() }, { viewModel.regenerate() })
                else Snackbar(Modifier.padding(16.dp)) { Text("Error: ${s.message}") }
                else -> {}
            }
        }
    }

    // Session context dialog
    val ctx = sessionContext
    if (ctx != null) {
        var editText by remember(ctx) { mutableStateOf(ctx.summary) }
        AlertDialog(
            onDismissRequest = { viewModel.dismissSessionContext() },
            icon = { Icon(Icons.Default.Description, null, tint = Primary) },
            title = { Text("Session Summary", fontWeight = FontWeight.SemiBold) },
            text = {
                Column {
                    OutlinedTextField(
                        value = editText, onValueChange = { editText = it },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 130.dp),
                        label = { Text("Summary") },
                        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Primary)
                    )
                    if (ctx.facts.isNotBlank()) {
                        Spacer(Modifier.height(12.dp))
                        Text("Facts · auto-generated", style = MaterialTheme.typography.labelMedium, color = TextSecondary)
                        Spacer(Modifier.height(4.dp))
                        Surface(shape = MaterialTheme.shapes.small, color = Background) {
                            Text(ctx.facts.take(500), Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp), color = TextSecondary)
                        }
                    }
                }
            },
            confirmButton = { Button(onClick = { viewModel.updateSessionSummary(editText) }, colors = ButtonDefaults.buttonColors(containerColor = Primary)) { Text("Save") } },
            dismissButton = { TextButton(onClick = { viewModel.dismissSessionContext() }) { Text("Close") } }
        )
    }
}
