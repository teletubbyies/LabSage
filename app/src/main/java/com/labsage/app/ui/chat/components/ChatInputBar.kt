package com.labsage.app.ui.chat.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.labsage.app.ui.theme.brandBrush

/**
 * One quick-action chip rendered above the input field. Built by [ChatScreen]
 * from the user's configured default quick actions (Settings).
 */
data class QuickChip(val label: String, val icon: ImageVector, val onClick: () -> Unit)

@Composable
fun ChatInputBar(
    value: String,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    quickChips: List<QuickChip> = emptyList(),
    /** Voice-input callback — recognized text is appended to the field. */
    onVoiceResult: (String) -> Unit = {}
) {
    val scheme = MaterialTheme.colorScheme

    Surface(
        modifier = modifier.fillMaxWidth(),
        shadowElevation = 8.dp,
        color = scheme.surface,
        tonalElevation = 0.dp
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp)
        ) {
            // Quick-action chips
            AnimatedVisibility(visible = quickChips.isNotEmpty(), enter = fadeIn(), exit = fadeOut()) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                    modifier = Modifier.padding(bottom = 8.dp)
                ) {
                    quickChips.forEach { QuickChip(it.label, it.icon, it.onClick, enabled) }
                }
            }

            // Field + voice + send
            Row(verticalAlignment = Alignment.Bottom) {
                OutlinedTextField(
                    value = value,
                    onValueChange = onValueChange,
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("向 LabSage 提问…", color = scheme.onSurfaceVariant) },
                    maxLines = 5,
                    minLines = 1,
                    shape = RoundedCornerShape(24.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = scheme.primary,
                        unfocusedBorderColor = scheme.outlineVariant,
                        focusedContainerColor = scheme.surface,
                        unfocusedContainerColor = scheme.surfaceVariant,
                        cursorColor = scheme.primary
                    ),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(
                        onSend = { if (enabled && value.isNotBlank()) onSend() }
                    )
                )
                Spacer(Modifier.width(8.dp))
                VoiceInputButton(onResult = onVoiceResult, enabled = enabled)
                Spacer(Modifier.width(8.dp))
                GradientSendButton(
                    enabled = enabled && value.isNotBlank(),
                    onClick = onSend
                )
            }
        }
    }
}

/** Circular send button on the brand gradient; muted + flat when disabled. */
@Composable
private fun GradientSendButton(enabled: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val size = Modifier.size(48.dp)
    Box(
        modifier = size
            .clip(CircleShape)
            .background(if (enabled) brandBrush() else SolidColor(scheme.surfaceVariant))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = ripple(bounded = false, radius = 24.dp),
                enabled = enabled,
                role = Role.Button,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            Icons.AutoMirrored.Filled.Send,
            "Send",
            tint = if (enabled) Color.White else scheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp)
        )
    }
}

@Composable
private fun QuickChip(label: String, icon: ImageVector, onClick: () -> Unit, enabled: Boolean) {
    val scheme = MaterialTheme.colorScheme

    Surface(
        onClick = { if (enabled) onClick() },
        enabled = enabled,
        shape = RoundedCornerShape(18.dp),
        color = scheme.primaryContainer,
        tonalElevation = 0.dp
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, null, Modifier.size(15.dp), tint = scheme.primary)
            Spacer(Modifier.width(5.dp))
            Text(label, style = MaterialTheme.typography.labelMedium, color = scheme.onPrimaryContainer, fontWeight = FontWeight.Medium)
        }
    }
}
