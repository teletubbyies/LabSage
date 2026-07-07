package com.labsage.app.ui.chat.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.labsage.app.domain.model.ToolCallStatus
import com.labsage.app.domain.model.ToolCallUi

/**
 * Collapsible tool-execution panel rendered as a vertical timeline of tool calls.
 *
 * Each tool call is an expandable card showing its status, duration, args and
 * result summary. Driven by [ChatViewModel.toolCalls] / [activeRoute].
 */
@Composable
fun ToolExecutionPanel(
    toolCalls: List<ToolCallUi>,
    route: String?,
    modifier: Modifier = Modifier
) {
    if (toolCalls.isEmpty() && route == null) return
    val scheme = MaterialTheme.colorScheme
    var expanded by rememberSaveable { mutableStateOf(false) }
    val completed = toolCalls.count { it.status == ToolCallStatus.COMPLETED }
    val calling = toolCalls.count { it.status == ToolCallStatus.CALLING }
    val failed = toolCalls.count { it.status == ToolCallStatus.ERROR }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        shape = RoundedCornerShape(16.dp),
        color = scheme.surfaceVariant.copy(alpha = 0.6f)
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.Build, null, Modifier.size(16.dp), tint = scheme.primary)
                Spacer(Modifier.width(8.dp))
                Text(
                    "工具执行",
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                    color = scheme.onSurface
                )
                if (route != null) {
                    Spacer(Modifier.width(8.dp))
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = scheme.primaryContainer
                    ) {
                        Text(
                            route,
                            Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = scheme.onPrimaryContainer,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
                Spacer(Modifier.weight(1f))
                // Compact status summary pills.
                Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (completed > 0) StatusBadge(Icons.Default.CheckCircle, completed, scheme.primary)
                    if (calling > 0) StatusBadge(Icons.Default.HourglassEmpty, calling, scheme.tertiary)
                    if (failed > 0) StatusBadge(Icons.Default.ErrorOutline, failed, scheme.error)
                }
                IconButton(onClick = { expanded = !expanded }, modifier = Modifier.size(28.dp)) {
                    Icon(
                        Icons.Default.ChevronRight, if (expanded) "收起" else "展开",
                        Modifier.size(20.dp).rotate(if (expanded) 90f else 0f),
                        tint = scheme.onSurfaceVariant
                    )
                }
            }

            AnimatedVisibility(visible = expanded) {
                Column(Modifier.padding(top = 4.dp)) {
                    toolCalls.forEachIndexed { idx, call ->
                        ToolCallCard(call, isLast = idx == toolCalls.lastIndex)
                    }
                }
            }
        }
    }
}

@Composable
private fun ToolCallCard(call: ToolCallUi, isLast: Boolean) {
    val scheme = MaterialTheme.colorScheme
    Row {
        // Timeline rail: status node + connector line
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            TimelineNode(call.status)
            if (!isLast) {
                Spacer(Modifier.height(2.dp))
                Box(
                    Modifier
                        .width(2.dp)
                        .height(38.dp)
                        .background(scheme.outlineVariant)
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        // Card body
        var showDetail by rememberSaveable(call.key) { mutableStateOf(false) }
        Surface(
            onClick = { showDetail = !showDetail },
            modifier = Modifier.weight(1f).padding(bottom = if (isLast) 0.dp else 6.dp),
            shape = RoundedCornerShape(12.dp),
            color = scheme.surface,
            tonalElevation = 0.dp
        ) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "#${call.index} ${call.name}",
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Medium
                        ),
                        color = scheme.onSurface,
                        modifier = Modifier.weight(1f)
                    )
                    StatusChip(call)
                }
                if (call.durationMs > 0) {
                    Spacer(Modifier.height(3.dp))
                    Text(
                        "${call.durationMs} ms",
                        style = MaterialTheme.typography.labelSmall,
                        color = scheme.onSurfaceVariant
                    )
                }
                AnimatedVisibility(visible = showDetail) {
                    Column {
                        if (call.argsText.isNotBlank()) {
                            DetailBlock("输入参数", call.argsText)
                        }
                        if (call.resultText.isNotBlank()) {
                            Spacer(Modifier.height(6.dp))
                            DetailBlock("输出摘要", call.resultText)
                        }
                        if (call.argsText.isBlank() && call.resultText.isBlank()) {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "无参数 / 输出",
                                style = MaterialTheme.typography.labelSmall,
                                color = scheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TimelineNode(status: ToolCallStatus) {
    val scheme = MaterialTheme.colorScheme
    val color = when (status) {
        ToolCallStatus.COMPLETED -> scheme.primary
        ToolCallStatus.ERROR -> scheme.error
        ToolCallStatus.CALLING -> scheme.tertiary
    }
    val icon: ImageVector = when (status) {
        ToolCallStatus.COMPLETED -> Icons.Default.CheckCircle
        ToolCallStatus.ERROR -> Icons.Default.ErrorOutline
        ToolCallStatus.CALLING -> Icons.Default.HourglassEmpty
    }
    // Pulse the in-flight node so "calling" reads as active.
    val transition = rememberInfiniteTransition(label = "node")
    val alpha by transition.animateFloat(
        initialValue = 0.4f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
        label = "nodeAlpha"
    )
    val tint = if (status == ToolCallStatus.CALLING) color.copy(alpha = alpha) else color
    Box(
        Modifier.size(18.dp).clip(CircleShape).background(color.copy(alpha = 0.16f)),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, null, Modifier.size(14.dp), tint = tint)
    }
}

@Composable
private fun StatusChip(call: ToolCallUi) {
    val scheme = MaterialTheme.colorScheme
    val (label, color) = when (call.status) {
        ToolCallStatus.COMPLETED -> "完成" to scheme.primary
        ToolCallStatus.ERROR -> "中断" to scheme.error
        ToolCallStatus.CALLING -> "调用中" to scheme.tertiary
    }
    Surface(
        shape = RoundedCornerShape(7.dp),
        color = color.copy(alpha = 0.16f)
    ) {
        Text(
            label,
            Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall.copy(color = color, fontWeight = FontWeight.Medium)
        )
    }
}

@Composable
private fun DetailBlock(label: String, content: String) {
    val scheme = MaterialTheme.colorScheme
    Column {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = scheme.onSurfaceVariant
        )
        Spacer(Modifier.height(3.dp))
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = scheme.surfaceVariant,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                content,
                Modifier.padding(horizontal = 9.dp, vertical = 6.dp),
                style = MaterialTheme.typography.labelSmall.copy(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp
                ),
                color = scheme.onSurface
            )
        }
    }
}

@Composable
private fun StatusBadge(icon: ImageVector, count: Int, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(13.dp), tint = color)
        Spacer(Modifier.width(3.dp))
        Text(
            "$count",
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
            color = color
        )
    }
}
