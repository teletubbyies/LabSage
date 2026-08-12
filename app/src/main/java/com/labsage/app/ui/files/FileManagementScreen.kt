package com.labsage.app.ui.files

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.labsage.app.LabSageApplication
import com.labsage.app.R
import com.labsage.app.data.remote.KnowledgeFileDto
import com.labsage.app.ui.files.FileManagementViewModel.UiState
import com.labsage.app.util.formatFileSize
import java.text.SimpleDateFormat
import java.util.*

/**
 * Knowledge-base file management page.
 *
 * Lists files from the backend (source of truth), supports name search and
 * real deletion (confirmed, server-side, with optimistic UI). Remote-first —
 * see [FileManagementViewModel] and `docs/files-api-contract.md`.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileManagementScreen(onBack: () -> Unit) {
    val app = LocalContext.current.applicationContext as LabSageApplication
    val viewModel: FileManagementViewModel = viewModel(factory = app.fileManagementViewModelFactory)
    val state by viewModel.state.collectAsStateWithLifecycle()
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    val refreshing by viewModel.refreshing.collectAsStateWithLifecycle()

    val scheme = MaterialTheme.colorScheme
    val context = LocalContext.current
    val isSearching = searchQuery.isNotBlank()
    val snackbarHostState = remember { SnackbarHostState() }

    var pendingDelete by remember { mutableStateOf<KnowledgeFileDto?>(null) }

    // One-shot ViewModel events → snackbars.
    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is FileManagementViewModel.UiEvent.DeleteFailed ->
                    snackbarHostState.showSnackbar(
                        context.getString(R.string.files_delete_failed, event.fileName)
                    )
                is FileManagementViewModel.UiEvent.LoadFailed ->
                    snackbarHostState.showSnackbar(
                        context.getString(R.string.files_load_failed, event.message)
                    )
            }
        }
    }

    Scaffold(
        containerColor = scheme.background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(R.string.files_title),
                        fontWeight = FontWeight.Bold,
                        color = scheme.onBackground
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            stringResource(R.string.action_back),
                            tint = scheme.onSurfaceVariant
                        )
                    }
                },
                actions = {
                    IconButton(onClick = viewModel::refresh, enabled = !refreshing) {
                        if (refreshing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp,
                                color = scheme.onSurfaceVariant
                            )
                        } else {
                            Icon(
                                Icons.Default.Refresh,
                                stringResource(R.string.files_refresh),
                                tint = scheme.onSurfaceVariant
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = scheme.surface)
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            // Search is only meaningful once we have data.
            if (state is UiState.Success) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = viewModel::setSearchQuery,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    placeholder = {
                        Text(
                            stringResource(R.string.files_search),
                            color = scheme.onSurfaceVariant
                        )
                    },
                    leadingIcon = { Icon(Icons.Default.Search, null, tint = scheme.onSurfaceVariant) },
                    trailingIcon = {
                        androidx.compose.animation.AnimatedVisibility(
                            visible = isSearching,
                            enter = androidx.compose.animation.fadeIn(),
                            exit = androidx.compose.animation.fadeOut()
                        ) {
                            IconButton(onClick = { viewModel.setSearchQuery("") }) {
                                Icon(
                                    Icons.Default.Close,
                                    stringResource(R.string.clear_search),
                                    tint = scheme.onSurfaceVariant
                                )
                            }
                        }
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color.Transparent,
                        unfocusedBorderColor = Color.Transparent,
                        focusedContainerColor = scheme.surfaceVariant,
                        unfocusedContainerColor = scheme.surfaceVariant,
                        cursorColor = scheme.primary
                    )
                )
            }

            when (val s = state) {
                UiState.Loading -> Centered { CircularProgressIndicator(color = scheme.primary) }

                is UiState.Error -> Centered {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Default.CloudOff,
                            null,
                            tint = scheme.onSurfaceVariant,
                            modifier = Modifier.size(40.dp)
                        )
                        Spacer(Modifier.height(10.dp))
                        Text(
                            s.message,
                            color = scheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Spacer(Modifier.height(12.dp))
                        Button(onClick = viewModel::retry) {
                            Text(stringResource(R.string.files_retry))
                        }
                    }
                }

                is UiState.Success -> {
                    val filtered = remember(s.files, searchQuery) {
                        if (searchQuery.isBlank()) s.files
                        else s.files.filter {
                            it.fileName.contains(searchQuery.trim(), ignoreCase = true)
                        }
                    }
                    when {
                        s.files.isEmpty() -> Centered { EmptyState() }
                        filtered.isEmpty() -> Centered { NoSearchResults() }
                        else -> PullToRefreshBox(
                            isRefreshing = refreshing,
                            onRefresh = viewModel::refresh,
                            modifier = Modifier.fillMaxSize()
                        ) {
                            LazyColumn(
                                Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 24.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                items(filtered, key = { it.id }) { file ->
                                    FileRow(
                                        file = file,
                                        onDelete = { pendingDelete = file }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // Delete confirmation (server-side delete is irreversible).
    pendingDelete?.let { file ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.files_delete)) },
            text = { Text(stringResource(R.string.files_delete_confirm, file.fileName)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.delete(file)
                        pendingDelete = null
                    }
                ) {
                    Text(stringResource(R.string.action_delete), color = scheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }
}

@Composable
private fun Centered(content: @Composable BoxScope.() -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center, content = content)
}

@Composable
private fun EmptyState() {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(Icons.Default.FolderOpen, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(40.dp))
        Spacer(Modifier.height(10.dp))
        Text(
            stringResource(R.string.files_empty),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

@Composable
private fun NoSearchResults() {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(Icons.Default.SearchOff, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(40.dp))
        Spacer(Modifier.height(10.dp))
        Text(
            stringResource(R.string.no_search_results),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

@Composable
private fun FileRow(file: KnowledgeFileDto, onDelete: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val dateFormat = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()) }
    val time = remember(file.uploadedAt) { dateFormat.format(Date(file.uploadedAt)) }

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = scheme.surface,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                fileIcon(file.fileName),
                null,
                tint = scheme.primary,
                modifier = Modifier.size(28.dp)
            )
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    file.fileName,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = scheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    "${formatFileSize(file.fileSize)}  ·  $time",
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.onSurfaceVariant
                )
            }
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Default.DeleteOutline,
                    stringResource(R.string.action_delete),
                    tint = scheme.onSurfaceVariant
                )
            }
        }
    }
}

private fun fileIcon(fileName: String): ImageVector {
    val ext = fileName.substringAfterLast('.', "").lowercase()
    return when (ext) {
        "pdf" -> Icons.Default.PictureAsPdf
        "png", "jpg", "jpeg", "gif", "webp", "bmp", "svg" -> Icons.Default.Image
        "doc", "docx", "txt", "md", "rtf" -> Icons.Default.Description
        "csv", "xls", "xlsx" -> Icons.Default.TableChart
        else -> Icons.AutoMirrored.Filled.InsertDriveFile
    }
}
