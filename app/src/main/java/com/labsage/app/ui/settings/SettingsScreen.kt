package com.labsage.app.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.DesktopWindows
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.labsage.app.LabSageApplication
import com.labsage.app.R
import com.labsage.app.domain.model.AppSettings
import com.labsage.app.domain.model.QuickAction
import com.labsage.app.domain.model.ThemeMode
import com.labsage.app.ui.chat.components.BrandMark

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val app = androidx.compose.ui.platform.LocalContext.current.applicationContext as LabSageApplication
    val viewModel: SettingsViewModel = viewModel(factory = app.settingsViewModelFactory)
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val connectionState by viewModel.connectionState.collectAsStateWithLifecycle()
    val scheme = MaterialTheme.colorScheme

    Scaffold(
        containerColor = scheme.background,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings), fontWeight = FontWeight.SemiBold, color = scheme.onSurface) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back), tint = scheme.onSurface)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = scheme.surface)
            )
        }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Brand header
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                color = scheme.surface,
                tonalElevation = 0.dp,
                shadowElevation = 0.5.dp
            ) {
                Row(
                    Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    BrandMark(size = 48.dp)
                    Spacer(Modifier.width(14.dp))
                    Column {
                        Text(
                            stringResource(R.string.app_name),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = scheme.onSurface
                        )
                        Text(
                            "${stringResource(R.string.app_subtitle)} · v1.0",
                            style = MaterialTheme.typography.bodySmall,
                            color = scheme.onSurfaceVariant
                        )
                    }
                }
            }

            // ---------- Backend address ----------
            SectionCard(
                title = stringResource(R.string.backend_address),
                subtitle = stringResource(R.string.backend_address_subtitle),
                icon = Icons.Default.DesktopWindows
            ) {
                var url by remember(settings.baseUrl) { mutableStateOf(settings.baseUrl) }
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text(stringResource(R.string.base_url_label)) },
                    placeholder = { Text(AppSettings.DEFAULT_BASE_URL) },
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        keyboardType = KeyboardType.Uri,
                        capitalization = KeyboardCapitalization.None
                    ),
                    shape = RoundedCornerShape(14.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = scheme.primary,
                        cursorColor = scheme.primary
                    )
                )
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Button(
                        onClick = { viewModel.setBaseUrl(url) },
                        enabled = url.trim() != settings.baseUrl && url.isNotBlank(),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = scheme.primary)
                    ) { Text(stringResource(R.string.action_save)) }
                    Spacer(Modifier.width(10.dp))
                    OutlinedButton(
                        onClick = { viewModel.testConnection(url) },
                        enabled = url.isNotBlank(),
                        shape = RoundedCornerShape(12.dp)
                    ) { Text(stringResource(R.string.test_connection)) }
                }
                Spacer(Modifier.height(6.dp))
                ConnectionStatus(connectionState)
            }

            // ---------- Appearance ----------
            SectionCard(
                title = stringResource(R.string.appearance),
                subtitle = stringResource(R.string.appearance_subtitle),
                icon = Icons.Default.Palette
            ) {
                ThemePicker(settings.themeMode, viewModel::setThemeMode)
            }

            // ---------- Model ----------
            SectionCard(
                title = stringResource(R.string.model),
                subtitle = stringResource(R.string.model_subtitle),
                icon = Icons.Default.Tune
            ) {
                var expanded by remember { mutableStateOf(false) }
                var model by remember(settings.model) { mutableStateOf(settings.model) }
                ExposedDropdownMenuBox(
                    expanded = expanded,
                    onExpandedChange = { expanded = !expanded }
                ) {
                    OutlinedTextField(
                        value = model,
                        onValueChange = {},
                        readOnly = true,
                        modifier = Modifier
                            .menuAnchor(MenuAnchorType.PrimaryNotEditable, enabled = true)
                            .fillMaxWidth(),
                        label = { Text(stringResource(R.string.preferred_model)) },
                        trailingIcon = {
                            ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded)
                        },
                        shape = RoundedCornerShape(14.dp)
                    )
                    ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        AppSettings.MODEL_OPTIONS.forEach { option ->
                            DropdownMenuItem(
                                text = { Text(option) },
                                trailingIcon = {
                                    if (option == model) Icon(Icons.Default.CheckCircle, null, tint = scheme.primary)
                                },
                                onClick = {
                                    model = option
                                    viewModel.setModel(option)
                                    expanded = false
                                }
                            )
                        }
                    }
                }
            }

            // ---------- Approval mode ----------
            SectionCard(
                title = stringResource(R.string.approval_mode),
                subtitle = stringResource(R.string.approval_mode_subtitle),
                icon = Icons.Default.Verified
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Verified, null, Modifier.size(20.dp), tint = scheme.primary)
                    Spacer(Modifier.width(10.dp))
                    Text(stringResource(R.string.confirm_before_sending), modifier = Modifier.weight(1f))
                    Switch(checked = settings.approvalMode, onCheckedChange = viewModel::setApprovalMode)
                }
            }

            // ---------- Default quick actions ----------
            SectionCard(
                title = stringResource(R.string.default_quick_actions),
                subtitle = stringResource(R.string.default_quick_actions_subtitle),
                icon = Icons.Default.Bolt
            ) {
                QuickActionToggle(stringResource(R.string.gpu_status), Icons.Filled.Memory, QuickAction.GPU, settings.quickActions, viewModel::toggleQuickAction)
                Spacer(Modifier.height(6.dp))
                QuickActionToggle(stringResource(R.string.conda_environment), Icons.Filled.Terminal, QuickAction.CONDA, settings.quickActions, viewModel::toggleQuickAction)
            }

            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun SectionCard(
    title: String,
    subtitle: String?,
    icon: ImageVector,
    content: @Composable ColumnScope.() -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = scheme.surface,
        tonalElevation = 0.dp,
        shadowElevation = 0.5.dp
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(34.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(scheme.primaryContainer.copy(alpha = 0.7f), RoundedCornerShape(10.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(icon, null, Modifier.size(19.dp), tint = scheme.primary)
                }
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(title, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold), color = scheme.onSurface)
                    if (subtitle != null) {
                        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
            content()
        }
    }
}

@Composable
private fun ConnectionStatus(state: SettingsViewModel.ConnectionState) {
    val scheme = MaterialTheme.colorScheme
    when (state) {
        SettingsViewModel.ConnectionState.Idle -> {}
        SettingsViewModel.ConnectionState.Testing -> Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = scheme.primary)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.connection_testing), style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
        }
        is SettingsViewModel.ConnectionState.Ok -> Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.CheckCircle, null, Modifier.size(16.dp), tint = scheme.primary)
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.connection_success, state.latencyMs), style = MaterialTheme.typography.bodySmall, color = scheme.primary, fontWeight = FontWeight.Medium)
        }
        is SettingsViewModel.ConnectionState.Failed -> Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.CloudOff, null, Modifier.size(16.dp), tint = scheme.error)
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.connection_failed, state.message), style = MaterialTheme.typography.bodySmall, color = scheme.error, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun ThemePicker(current: ThemeMode, onSelect: (ThemeMode) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    data class Option(val mode: ThemeMode, val icon: ImageVector, val label: String)
    val options = listOf(
        Option(ThemeMode.SYSTEM, Icons.Filled.DesktopWindows, stringResource(R.string.theme_system)),
        Option(ThemeMode.LIGHT, Icons.Filled.LightMode, stringResource(R.string.theme_light)),
        Option(ThemeMode.DARK, Icons.Filled.DarkMode, stringResource(R.string.theme_dark))
    )

    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        options.forEach { (mode, icon, label) ->
            val selected = current == mode
            Surface(
                onClick = { onSelect(mode) },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(14.dp),
                color = if (selected) scheme.primaryContainer else scheme.surfaceVariant,
                border = if (selected) androidx.compose.foundation.BorderStroke(2.dp, scheme.primary) else null
            ) {
                Column(
                    Modifier.padding(vertical = 16.dp).fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        Modifier.size(40.dp).clip(CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            icon, null,
                            modifier = Modifier.size(24.dp),
                            tint = if (selected) scheme.primary else scheme.onSurfaceVariant
                        )
                        if (selected) {
                            Icon(
                                Icons.Filled.CheckCircle, null,
                                modifier = Modifier
                                    .size(14.dp)
                                    .align(Alignment.BottomEnd)
                                    .clip(CircleShape),
                                tint = scheme.primary
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        label,
                        style = MaterialTheme.typography.labelMedium,
                        color = if (selected) scheme.onPrimaryContainer else scheme.onSurfaceVariant,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
                    )
                }
            }
        }
    }
}

@Composable
private fun QuickActionToggle(
    label: String,
    icon: ImageVector,
    action: QuickAction,
    enabled: List<QuickAction>,
    onToggle: (QuickAction) -> Unit
) {
    val checked = action in enabled
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Icon(icon, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(10.dp))
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = { onToggle(action) })
    }
}
