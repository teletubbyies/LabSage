package com.labsage.app.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.DesktopWindows
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.labsage.app.LabSageApplication
import com.labsage.app.domain.model.AppSettings
import com.labsage.app.domain.model.QuickAction
import com.labsage.app.domain.model.ThemeMode

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
                title = { Text("设置", fontWeight = FontWeight.SemiBold, color = scheme.onBackground) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回", tint = scheme.onBackground)
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
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            // ---------- Backend address ----------
            SectionCard(title = "后端地址", subtitle = "修改后新请求即时生效，无需重启") {
                var url by remember(settings.baseUrl) { mutableStateOf(settings.baseUrl) }
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("Base URL") },
                    placeholder = { Text(AppSettings.DEFAULT_BASE_URL) },
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        keyboardType = KeyboardType.Uri,
                        capitalization = KeyboardCapitalization.None
                    ),
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = scheme.primary)
                )
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Button(
                        onClick = { viewModel.setBaseUrl(url) },
                        enabled = url.trim() != settings.baseUrl && url.isNotBlank(),
                        colors = ButtonDefaults.buttonColors(containerColor = scheme.primary)
                    ) { Text("保存") }
                    Spacer(Modifier.width(10.dp))
                    OutlinedButton(
                        onClick = { viewModel.testConnection(url) },
                        enabled = url.isNotBlank()
                    ) { Text("测试连接") }
                }
                Spacer(Modifier.height(6.dp))
                ConnectionStatus(connectionState)
            }

            // ---------- Appearance ----------
            SectionCard(title = "外观", subtitle = "暗黑模式已支持系统跟随，可手动覆盖") {
                ThemePicker(settings.themeMode, viewModel::setThemeMode)
            }

            // ---------- Model ----------
            SectionCard(title = "模型", subtitle = "实验性 · 需后端配合读取生效") {
                var expanded by remember { mutableStateOf(false) }
                var model by remember(settings.model) { mutableStateOf(settings.model) }
                Box {
                    OutlinedTextField(
                        value = model,
                        onValueChange = {},
                        readOnly = true,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("首选模型") },
                        trailingIcon = {
                            IconButton(onClick = { expanded = true }) {
                                Icon(Icons.Filled.DesktopWindows, "选择", tint = scheme.onSurfaceVariant)
                            }
                        },
                        shape = RoundedCornerShape(12.dp)
                    )
                    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        AppSettings.MODEL_OPTIONS.forEach { option ->
                            DropdownMenuItem(
                                text = { Text(option) },
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
            SectionCard(title = "工具审批模式", subtitle = "开启后，发送消息前需确认（防误触发工具调用）") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Verified, null, Modifier.size(20.dp), tint = scheme.primary)
                    Spacer(Modifier.width(10.dp))
                    Text("发送前确认", modifier = Modifier.weight(1f))
                    Switch(checked = settings.approvalMode, onCheckedChange = viewModel::setApprovalMode)
                }
            }

            // ---------- Default quick actions ----------
            SectionCard(title = "默认快捷操作", subtitle = "输入栏显示的快捷按钮") {
                QuickActionToggle("GPU 状态", Icons.Filled.Memory, QuickAction.GPU, settings.quickActions, viewModel::toggleQuickAction)
                QuickActionToggle("Conda 环境", Icons.Filled.Terminal, QuickAction.CONDA, settings.quickActions, viewModel::toggleQuickAction)
            }

            Spacer(Modifier.height(8.dp))
            Text(
                "LabSage ${"·"} 设置即时持久化（DataStore）",
                style = MaterialTheme.typography.labelSmall,
                color = scheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun SectionCard(title: String, subtitle: String?, content: @Composable ColumnScope.() -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = scheme.surface,
        tonalElevation = 0.dp,
        shadowElevation = 0.5.dp
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold), color = scheme.onSurface)
            if (subtitle != null) {
                Spacer(Modifier.height(2.dp))
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.height(12.dp))
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
            Text("正在测试…", style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
        }
        is SettingsViewModel.ConnectionState.Ok -> Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.CheckCircle, null, Modifier.size(16.dp), tint = scheme.primary)
            Spacer(Modifier.width(6.dp))
            Text("连接成功 · ${state.latencyMs} ms", style = MaterialTheme.typography.bodySmall, color = scheme.primary)
        }
        is SettingsViewModel.ConnectionState.Failed -> Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.CloudOff, null, Modifier.size(16.dp), tint = scheme.error)
            Spacer(Modifier.width(6.dp))
            Text("连接失败 · ${state.message}", style = MaterialTheme.typography.bodySmall, color = scheme.error, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun ThemePicker(current: ThemeMode, onSelect: (ThemeMode) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    data class Option(val mode: ThemeMode, val icon: androidx.compose.ui.graphics.vector.ImageVector, val label: String)
    val options = listOf(
        Option(ThemeMode.SYSTEM, Icons.Filled.DesktopWindows, "跟随系统"),
        Option(ThemeMode.LIGHT, Icons.Filled.LightMode, "浅色"),
        Option(ThemeMode.DARK, Icons.Filled.DarkMode, "深色")
    )

    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (mode, icon, label) ->
            val selected = current == mode
            Surface(
                onClick = { onSelect(mode) },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(12.dp),
                color = if (selected) scheme.primaryContainer else scheme.surfaceVariant.copy(alpha = 0.5f)
            ) {
                Column(
                    Modifier.padding(vertical = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(icon, null, tint = if (selected) scheme.primary else scheme.onSurfaceVariant)
                    Spacer(Modifier.height(4.dp))
                    Text(label, style = MaterialTheme.typography.labelMedium, color = if (selected) scheme.onPrimaryContainer else scheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun QuickActionToggle(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
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
