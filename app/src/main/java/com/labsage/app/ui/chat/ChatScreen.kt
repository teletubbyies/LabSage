package com.labsage.app.ui.chat

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.*
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.core.content.ContextCompat
import com.labsage.app.LabSageApplication
import com.labsage.app.R
import com.labsage.app.domain.model.MessageReference
import com.labsage.app.domain.model.QuickAction
import com.labsage.app.domain.model.StreamState
import com.labsage.app.ui.chat.components.*
import com.labsage.app.ui.theme.LocalExtendedColors
import dev.jeziellago.compose.markdowntext.MarkdownText
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ChatScreen(
    conversationId: String,
    targetMessageId: String? = null,
    onBack: () -> Unit
) {
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
    val historySyncState by viewModel.historySyncState.collectAsStateWithLifecycle()
    val uploadState by viewModel.uploadState.collectAsStateWithLifecycle()
    val settings by app.appSettings.collectAsStateWithLifecycle()

    var inputText by remember { mutableStateOf("") }
    // Citation detail sheet. The open-citation lambda is remembered so that
    // MessageBubble receives a stable reference and stays skippable.
    var citationSheet by remember { mutableStateOf<MessageReference?>(null) }
    val openCitation = remember<(MessageReference) -> Unit> {
        { citation -> citationSheet = citation }
    }
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()
    val isStreaming by remember { derivedStateOf { streamState is StreamState.Streaming } }
    val isUserDragging by listState.interactionSource.collectIsDraggedAsState()
    val hasStreamingIndicator = isStreaming && messages.none { it.role == "assistant" && it.isPartial }
    val hasToolPanel = toolCalls.isNotEmpty() || activeRoute != null
    val bottomAnchorIndex = messages.size +
        (if (hasStreamingIndicator) 1 else 0) +
        (if (hasToolPanel) 1 else 0)
    val isAtBottom by remember {
        derivedStateOf {
            val layoutInfo = listState.layoutInfo
            layoutInfo.totalItemsCount == 0 ||
                layoutInfo.visibleItemsInfo.lastOrNull()?.index == layoutInfo.totalItemsCount - 1
        }
    }
    var autoFollow by remember(conversationId) { mutableStateOf(true) }
    var initialScrollCompleted by remember(conversationId) { mutableStateOf(false) }

    // Build quick actions after autoFollow so a user-triggered tool request always
    // brings its new output into view, even if the user had scrolled upward.
    val quickChips = remember(settings.quickActions) {
        settings.quickActions.mapNotNull { action ->
            when (action) {
                QuickAction.GPU -> QuickChip("GPU", Icons.Default.Memory) {
                    autoFollow = true
                    viewModel.queryGpuStatus()
                }
                QuickAction.CONDA -> QuickChip("Conda", Icons.Default.Terminal) {
                    autoFollow = true
                    viewModel.queryCondaEnv()
                }
            }
        }
    }

    var consumedTargetMessageId by remember(conversationId) { mutableStateOf<String?>(null) }
    val hasPendingMessageTarget = targetMessageId != null &&
        consumedTargetMessageId != targetMessageId

    // Ask at the moment the feature becomes relevant, not on cold app launch.
    var notificationPermissionRequested by rememberSaveable { mutableStateOf(false) }
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }
    LaunchedEffect(isStreaming) {
        if (
            isStreaming &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !notificationPermissionRequested &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionRequested = true
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

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
        uri?.let { viewModel.uploadFile(it) }
    }

    // A user drag decides whether future stream chunks keep following the bottom.
    LaunchedEffect(isUserDragging) {
        if (!isUserDragging) autoFollow = isAtBottom
    }

    // Initial load and newly appended messages target a dedicated bottom anchor,
    // so a very tall last message is positioned at its actual end, not its top.
    LaunchedEffect(messages.size, hasPendingMessageTarget, hasStreamingIndicator, hasToolPanel) {
        if (messages.isEmpty() || hasPendingMessageTarget) return@LaunchedEffect
        if (!initialScrollCompleted) {
            listState.scrollToBottom(bottomAnchorIndex, animated = false)
            initialScrollCompleted = true
        } else if (autoFollow) {
            listState.scrollToBottom(bottomAnchorIndex, animated = true)
        }
    }

    // Snap on stream chunks to avoid stacking hundreds of scroll animations.
    LaunchedEffect(messages.lastOrNull()?.content) {
        if (isStreaming && initialScrollCompleted && autoFollow) {
            listState.scrollToBottom(bottomAnchorIndex, animated = false)
        }
    }

    // Stream end: the bubble re-renders from raw partial text to formatted
    // markdown (usually taller) and the completion banner appears below the
    // list, so the last per-chunk follow lands above the true bottom. None of
    // the effects above re-run for that re-layout — wait one frame for it to
    // settle, then snap once more to the real bottom. Guarded on the last
    // message being an assistant reply: a just-sent user message must keep
    // effect #1's animated scroll (this instant snap would cancel it).
    LaunchedEffect(isStreaming, messages.lastOrNull()?.content, justCompleted) {
        if (messages.lastOrNull()?.role != "assistant") return@LaunchedEffect
        if (isStreaming || !initialScrollCompleted || !autoFollow) return@LaunchedEffect
        withFrameNanos { }
        if (autoFollow) listState.scrollToBottom(bottomAnchorIndex, animated = false)
    }
    // Notification navigation waits for Room's first emission, then positions the
    // exact persisted assistant message instead of merely opening the conversation.
    LaunchedEffect(targetMessageId, messages) {
        val target = targetMessageId ?: return@LaunchedEffect
        if (consumedTargetMessageId == target) return@LaunchedEffect
        val targetIndex = messages.indexOfFirst { it.id == target }
        if (targetIndex >= 0) {
            listState.scrollToItem(targetIndex)
            consumedTargetMessageId = target
            initialScrollCompleted = true
            autoFollow = false
        }
    }

    var showMoreActions by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = scheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        BrandMark(size = 30.dp)
                        Spacer(Modifier.width(10.dp))
                        Text(stringResource(R.string.app_name), fontWeight = FontWeight.Bold, color = scheme.onSurface)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back), tint = scheme.onSurface)
                    }
                },
                actions = {
                    IconButton(
                        enabled = uploadState !is UploadState.Uploading,
                        // Some document providers expose Markdown as
                        // application/octet-stream; validate the extension after selection.
                        onClick = { filePicker.launch("*/*") }
                    ) {
                        Icon(Icons.Default.AttachFile, stringResource(R.string.attach_file), tint = scheme.onSurfaceVariant)
                    }
                    Box {
                        IconButton(onClick = { showMoreActions = true }) {
                            Icon(Icons.Default.MoreVert, stringResource(R.string.more_chat_actions), tint = scheme.onSurfaceVariant)
                        }
                        DropdownMenu(expanded = showMoreActions, onDismissRequest = { showMoreActions = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.session_summary)) },
                                leadingIcon = { Icon(Icons.Default.Description, null) },
                                onClick = {
                                    showMoreActions = false
                                    viewModel.loadSessionContext()
                                }
                            )
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.query_gpu_status)) },
                                leadingIcon = { Icon(Icons.Default.Memory, null) },
                                onClick = {
                                    showMoreActions = false
                                    autoFollow = true
                                    viewModel.queryGpuStatus()
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.query_conda_environment)) },
                                leadingIcon = { Icon(Icons.Default.Terminal, null) },
                                onClick = {
                                    showMoreActions = false
                                    autoFollow = true
                                    viewModel.queryCondaEnv()
                                }
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = scheme.surface)
            )
        },
        bottomBar = {
            ChatInputBar(value = inputText, onValueChange = { inputText = it },
                onSend = { if (inputText.isNotBlank()) { autoFollow = true; viewModel.sendMessage(inputText.trim()); inputText = "" } },
                enabled = !isStreaming, quickChips = quickChips)
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            SyncStatusBanner(historySyncState, viewModel::refreshMessages)
            // Offline banner
            if (!isOnline) {
                Surface(Modifier.fillMaxWidth(), color = scheme.errorContainer) {
                    Row(Modifier.padding(horizontal = 16.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.CloudOff, null, Modifier.size(16.dp), tint = scheme.onErrorContainer)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.no_network_connection), style = MaterialTheme.typography.labelMedium, color = scheme.onErrorContainer)
                    }
                }
            }

            if (uploadState is UploadState.Uploading) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    Text(
                        stringResource(R.string.upload_in_progress),
                        style = MaterialTheme.typography.labelMedium,
                        color = scheme.primary
                    )
                    Spacer(Modifier.height(6.dp))
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            }

            Box(Modifier.weight(1f)) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    state = listState,
                    contentPadding = PaddingValues(vertical = 8.dp)
                ) {
                    items(messages, key = { it.id }) { msg ->
                        var showMenu by remember { mutableStateOf(false) }
                        // combinedClickable lives on the outer Box, NOT on MessageBubble's modifier.
                        // Passing a Modifier built with fresh lambdas each recomposition
                        // (onClick = {}, onLongClick = { showMenu = true }) makes MessageBubble's
                        // `modifier` param always "changed", which defeats Compose's skip — every
                        // visible bubble recomposes on each streaming chunk (measured 8/8). Keeping
                        // the click handler here leaves MessageBubble with a constant Modifier, so
                        // historical bubbles are skipped and only the streaming one recomposes
                        // (measured 8/8 → 1/8). See app/src/androidTest/.../ui/perf/.
                        Box(
                            modifier = Modifier.combinedClickable(
                                onClick = {},
                                onLongClick = { showMenu = true }
                            )
                        ) {
                            MessageBubble(
                                role = msg.role,
                                content = msg.content,
                                isStreaming = msg.isPartial && isStreaming,
                                traceLines = if (msg.isPartial && isStreaming && msg.role == "assistant") {
                                    traceLines
                                } else {
                                    emptyList()
                                },
                                citations = msg.citations,
                                onCitationClick = openCitation
                            )
                            DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                                DropdownMenuItem(text = { Text(stringResource(R.string.copy_message)) }, leadingIcon = { Icon(Icons.Default.ContentCopy, null) }, onClick = {
                                    showMenu = false
                                    (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                                        .setPrimaryClip(ClipData.newPlainText("message", msg.content))
                                    Toast.makeText(context, context.getString(R.string.message_copied), Toast.LENGTH_SHORT).show()
                                })
                                DropdownMenuItem(text = { Text(stringResource(R.string.share_message)) }, leadingIcon = { Icon(Icons.Default.Share, null) }, onClick = {
                                    showMenu = false
                                    context.startActivity(
                                        Intent.createChooser(
                                            Intent(Intent.ACTION_SEND).apply {
                                                type = "text/plain"
                                                putExtra(Intent.EXTRA_TEXT, msg.content)
                                            },
                                            context.getString(R.string.share_message_chooser)
                                        )
                                    )
                                })
                                if (msg.role == "user") {
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.re_edit_message)) },
                                        leadingIcon = { Icon(Icons.Default.Edit, null) },
                                        onClick = {
                                            showMenu = false
                                            inputText = msg.content
                                        }
                                    )
                                }
                            }
                        }
                    }
                    if (hasStreamingIndicator) {
                        item(key = "streaming_indicator") { StreamingIndicator() }
                    }
                    if (hasToolPanel) {
                        item(key = "tool_panel") {
                            ToolExecutionPanel(toolCalls = toolCalls, route = activeRoute)
                        }
                    }
                    item(key = "bottom_anchor") { Spacer(Modifier.height(1.dp)) }
                }

                if (messages.isNotEmpty() && !isAtBottom) {
                    FilledTonalIconButton(
                        modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp),
                        onClick = {
                            autoFollow = true
                            coroutineScope.launch { listState.scrollToBottom(bottomAnchorIndex, animated = true) }
                        }
                    ) {
                        Icon(Icons.Default.ArrowDownward, stringResource(R.string.scroll_to_bottom))
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
                        shape = RoundedCornerShape(12.dp), color = ext.completionBg
                    ) {
                        Row(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.CheckCircle, null, Modifier.size(16.dp), tint = scheme.primary)
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.response_complete), style = MaterialTheme.typography.labelMedium, color = scheme.primary, fontWeight = FontWeight.Medium)
                        }
                    }
                }
            }

            when (val s = streamState) {
                is StreamState.Error -> if (s.retryable) PartialMessageBanner(s.message, { viewModel.continueGeneration() }, { viewModel.regenerate() })
                else Snackbar(Modifier.padding(16.dp)) { Text(stringResource(R.string.error_message, s.message)) }
                else -> {}
            }
            val upload = uploadState
            if (upload is UploadState.Error) {
                Snackbar(
                    modifier = Modifier.padding(16.dp),
                    action = {
                        TextButton(onClick = viewModel::clearUploadState) {
                            Text(stringResource(R.string.action_close))
                        }
                    }
                ) {
                    Text(stringResource(R.string.upload_failed, upload.message))
                }
            }
        }
    }

    // Approval gate — visible only when approval mode stages a pending send.
    val pending = pendingSend
    if (pending != null) {
        AlertDialog(
            onDismissRequest = { viewModel.cancelPendingSend() },
            icon = { Icon(Icons.Default.Verified, null, tint = scheme.primary) },
            title = { Text(stringResource(R.string.approval_title), fontWeight = FontWeight.SemiBold) },
            text = {
                Column {
                    Text(stringResource(R.string.approval_description), style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(8.dp))
                    Surface(shape = RoundedCornerShape(12.dp), color = scheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
                        Text(pending, Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            },
            confirmButton = {
                Button(onClick = { viewModel.confirmPendingSend() }, shape = RoundedCornerShape(12.dp), colors = ButtonDefaults.buttonColors(containerColor = scheme.primary)) {
                    Text(stringResource(R.string.allow_send))
                }
            },
            dismissButton = { TextButton(onClick = { viewModel.cancelPendingSend() }) { Text(stringResource(R.string.action_cancel)) } }
        )
    }

    // Session context editor and structured facts manager.
    val ctx = sessionContext
    if (ctx != null) {
        SessionContextSheet(
            context = ctx,
            onDismiss = viewModel::dismissSessionContext,
            onSaveSummary = viewModel::updateSessionSummary,
            onDeleteFact = viewModel::deleteSessionFact,
            onClearError = viewModel::clearSessionContextError
        )
    }

    // Citation detail — chunk metadata of the tapped reference chip.
    val citation = citationSheet
    if (citation != null) {
        ModalBottomSheet(onDismissRequest = { citationSheet = null }) {
            CitationDetailSheetContent(citation)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SessionContextSheet(
    context: SessionContextUi,
    onDismiss: () -> Unit,
    onSaveSummary: (String) -> Unit,
    onDeleteFact: (String) -> Unit,
    onClearError: () -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var editText by remember(context.summary) { mutableStateOf(context.summary) }
    var showPreview by rememberSaveable { mutableStateOf(false) }
    var pendingDeletion by remember { mutableStateOf<SessionFactUi?>(null) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = scheme.surface,
        dragHandle = { BottomSheetDefaults.DragHandle() }
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.92f)
                .imePadding(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item(key = "context_header") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = scheme.primaryContainer,
                        modifier = Modifier.size(42.dp)
                    ) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.Description, null, tint = scheme.primary)
                        }
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            stringResource(R.string.session_summary),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            stringResource(R.string.summary_editor_description),
                            style = MaterialTheme.typography.bodySmall,
                            color = scheme.onSurfaceVariant
                        )
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, stringResource(R.string.action_close))
                    }
                }
            }

            if (context.isSaving) {
                item(key = "context_progress") {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            }

            context.errorMessage?.let { error ->
                item(key = "context_error") {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = scheme.errorContainer
                    ) {
                        Row(
                            Modifier.fillMaxWidth().padding(start = 12.dp, top = 8.dp, bottom = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.ErrorOutline, null, tint = scheme.onErrorContainer)
                            Spacer(Modifier.width(8.dp))
                            Text(
                                stringResource(R.string.context_operation_failed, error),
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.bodySmall,
                                color = scheme.onErrorContainer
                            )
                            IconButton(onClick = onClearError) {
                                Icon(Icons.Default.Close, stringResource(R.string.action_close), tint = scheme.onErrorContainer)
                            }
                        }
                    }
                }
            }

            item(key = "summary_editor") {
                Column {
                    Text(
                        stringResource(R.string.session_summary),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = editText,
                        onValueChange = { editText = it },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 130.dp, max = 220.dp),
                        label = { Text(stringResource(R.string.summary_markdown_label)) },
                        enabled = !context.isSaving,
                        shape = RoundedCornerShape(14.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = scheme.primary,
                            cursorColor = scheme.primary
                        )
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(onClick = { showPreview = !showPreview }) {
                            Icon(
                                if (showPreview) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                null,
                                Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(if (showPreview) R.string.hide_preview else R.string.show_preview))
                        }
                        Spacer(Modifier.width(8.dp))
                        Button(
                            onClick = { onSaveSummary(editText.trim()) },
                            enabled = !context.isSaving && editText.trim() != context.summary,
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text(stringResource(R.string.save_summary))
                        }
                    }
                }
            }

            if (showPreview) {
                item(key = "summary_preview") {
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = scheme.surfaceVariant,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (editText.isNotBlank()) {
                            MarkdownText(markdown = editText, modifier = Modifier.padding(14.dp))
                        } else {
                            Text(
                                stringResource(R.string.summary_preview_placeholder),
                                Modifier.padding(14.dp),
                                style = MaterialTheme.typography.bodySmall,
                                color = scheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            item(key = "facts_divider") { HorizontalDivider() }

            item(key = "facts_header") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            stringResource(R.string.structured_facts),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            stringResource(R.string.facts_count, context.facts.size),
                            style = MaterialTheme.typography.bodySmall,
                            color = scheme.onSurfaceVariant
                        )
                    }
                    Icon(Icons.Default.DataObject, null, tint = scheme.primary)
                }
            }

            when {
                !context.factsJsonValid -> {
                    item(key = "facts_invalid") {
                        InvalidFactsCard(context.factsRaw)
                    }
                }
                context.facts.isEmpty() -> {
                    item(key = "facts_empty") {
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = scheme.surfaceVariant.copy(alpha = 0.65f),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                Modifier.padding(horizontal = 16.dp, vertical = 18.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Icon(Icons.Default.Inventory2, null, tint = scheme.onSurfaceVariant)
                                Spacer(Modifier.height(8.dp))
                                Text(stringResource(R.string.facts_empty), fontWeight = FontWeight.Medium)
                                Spacer(Modifier.height(3.dp))
                                Text(
                                    stringResource(R.string.facts_empty_description),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = scheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
                else -> {
                    items(context.facts, key = { "fact_${it.key}" }) { fact ->
                        FactCard(
                            fact = fact,
                            deleteEnabled = !context.isSaving,
                            onDelete = { pendingDeletion = fact }
                        )
                    }
                }
            }
        }
    }

    pendingDeletion?.let { fact ->
        AlertDialog(
            onDismissRequest = { pendingDeletion = null },
            icon = { Icon(Icons.Default.DeleteOutline, null, tint = scheme.error) },
            title = { Text(stringResource(R.string.delete_fact)) },
            text = { Text(stringResource(R.string.delete_fact_confirmation, fact.key)) },
            confirmButton = {
                Button(
                    onClick = {
                        pendingDeletion = null
                        onDeleteFact(fact.key)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = scheme.error)
                ) {
                    Text(stringResource(R.string.action_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeletion = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }
}

@Composable
private fun FactCard(
    fact: SessionFactUi,
    deleteEnabled: Boolean,
    onDelete: () -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = scheme.surfaceVariant.copy(alpha = 0.72f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(start = 14.dp, top = 10.dp, end = 6.dp, bottom = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.fact_key),
                        style = MaterialTheme.typography.labelSmall,
                        color = scheme.onSurfaceVariant
                    )
                    Text(
                        fact.key,
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.SemiBold
                        )
                    )
                }
                IconButton(onClick = onDelete, enabled = deleteEnabled) {
                    Icon(
                        Icons.Default.DeleteOutline,
                        stringResource(R.string.delete_fact),
                        tint = scheme.error
                    )
                }
            }
            HorizontalDivider(Modifier.padding(end = 8.dp))
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.fact_value),
                style = MaterialTheme.typography.labelSmall,
                color = scheme.onSurfaceVariant
            )
            Spacer(Modifier.height(3.dp))
            SelectionContainer {
                Text(
                    fact.value,
                    modifier = Modifier.padding(end = 8.dp),
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontFamily = if (fact.value.startsWith("{") || fact.value.startsWith("[")) {
                            FontFamily.Monospace
                        } else {
                            FontFamily.Default
                        }
                    )
                )
            }
        }
    }
}

@Composable
private fun InvalidFactsCard(rawFacts: String) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = scheme.errorContainer,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.WarningAmber, null, tint = scheme.onErrorContainer)
                Spacer(Modifier.width(8.dp))
                Text(
                    stringResource(R.string.facts_invalid_json),
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onErrorContainer
                )
            }
            if (rawFacts.isNotBlank()) {
                Spacer(Modifier.height(12.dp))
                Text(
                    stringResource(R.string.raw_json),
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.onErrorContainer
                )
                Spacer(Modifier.height(4.dp))
                SelectionContainer {
                    Text(
                        rawFacts,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        color = scheme.onErrorContainer
                    )
                }
            }
        }
    }
}

private suspend fun LazyListState.scrollToBottom(bottomAnchorIndex: Int, animated: Boolean) {
    if (animated) animateScrollToItem(bottomAnchorIndex) else scrollToItem(bottomAnchorIndex)
}
