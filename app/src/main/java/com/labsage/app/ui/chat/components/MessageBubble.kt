package com.labsage.app.ui.chat.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.labsage.app.ui.theme.LocalExtendedColors
import dev.jeziellago.compose.markdowntext.MarkdownText

@Composable
fun MessageBubble(
    role: String,
    content: String,
    isStreaming: Boolean = false,
    traceLines: List<String> = emptyList(),
    modifier: Modifier = Modifier
) {
    val isUser = role == "user"
    val ext = LocalExtendedColors.current
    val scheme = MaterialTheme.colorScheme

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.Bottom
    ) {
        if (isUser) {
            Spacer(Modifier.weight(0.15f))
        }

        Column(
            modifier = Modifier.weight(0.85f, fill = false),
            horizontalAlignment = if (isUser) Alignment.End else Alignment.Start
        ) {
            // Inline trace (assistant only, during streaming)
            if (!isUser && traceLines.isNotEmpty() && isStreaming) {
                InlineTraceChip(traceLines)
                Spacer(Modifier.height(4.dp))
            }

            // Bubble
            Surface(
                shape = RoundedCornerShape(
                    topStart = 20.dp,
                    topEnd = 20.dp,
                    bottomStart = if (isUser) 20.dp else 4.dp,
                    bottomEnd = if (isUser) 4.dp else 20.dp
                ),
                color = if (isUser) ext.userBubble else ext.assistantBubble,
                shadowElevation = 1.dp,
                tonalElevation = 0.dp
            ) {
                Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                    if (!isUser && !isStreaming && content.isNotBlank()) {
                        // Completed assistant message — render Markdown
                        MarkdownText(markdown = content)
                    } else if (!isUser && isStreaming && content.isEmpty()) {
                        // Empty during streaming — "Thinking..."
                        Text(
                            "Thinking",
                            color = ext.assistantBubbleOn,
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Spacer(Modifier.height(2.dp))
                        ThinkingDots()
                    } else {
                        // User message or streaming partial — plain text
                        Text(
                            text = content.ifEmpty { "Thinking" },
                            color = if (isUser) ext.userBubbleOn else ext.assistantBubbleOn,
                            style = MaterialTheme.typography.bodyMedium.copy(
                                lineHeight = 22.sp, letterSpacing = 0.2.sp
                            )
                        )
                    }
                }
            }
        }

        if (!isUser) {
            Spacer(Modifier.weight(0.15f))
        }
    }
}

// ============================================================
// Inline trace chip — near avatar, during tool execution
// ============================================================

@Composable
private fun InlineTraceChip(traceLines: List<String>) {
    val ext = LocalExtendedColors.current
    val scheme = MaterialTheme.colorScheme

    val toolLine = traceLines.lastOrNull {
        it.contains("调用工具") || it.contains("开始调用") || it.contains("调用完成")
    }
    val (icon, text) = when {
        toolLine == null -> Icons.Default.Search to "Processing..."
        toolLine.contains("调用工具") -> {
            val name = toolLine.substringAfter("调用工具：").substringBefore("(").trim()
            Icons.Default.Build to "Calling $name..."
        }
        toolLine.contains("调用完成") -> Icons.Default.CheckCircle to "Tool completed"
        toolLine.contains("开始调用") -> {
            val parts = toolLine.substringAfter("开始调用").trim().take(30)
            Icons.Default.PlayArrow to parts
        }
        else -> Icons.Default.Search to "Analyzing..."
    }

    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .then(
                Modifier.background(ext.inlineTraceBg, RoundedCornerShape(12.dp))
            )
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, Modifier.size(12.dp), tint = scheme.primary)
        Spacer(Modifier.width(4.dp))
        Text(
            text, maxLines = 1, overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp, color = scheme.primary, fontWeight = FontWeight.Medium)
        )
    }
}

// ============================================================
// Thinking dots animation
// ============================================================

@Composable
fun ThinkingDots() {
    val scheme = MaterialTheme.colorScheme
    val infinite = rememberInfiniteTransition(label = "dots")
    Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        repeat(3) { i ->
            val alpha by infinite.animateFloat(
                initialValue = 0.2f, targetValue = 1f,
                animationSpec = infiniteRepeatable(tween(500, delayMillis = i * 200), RepeatMode.Reverse),
                label = "dot$i"
            )
            Box(Modifier.size(5.dp).clip(CircleShape).then(Modifier.background(scheme.primary.copy(alpha = alpha))))
        }
    }
}
