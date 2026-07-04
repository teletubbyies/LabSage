package com.labsage.app.ui.chat.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp

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
        shadowElevation = 4.dp,
        color = scheme.surface,
        tonalElevation = 0.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            // Main column: chips + text field (takes remaining space)
            Column(modifier = Modifier.weight(1f)) {
                if (quickChips.isNotEmpty()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        quickChips.forEach { QuickChip(it.label, it.icon, it.onClick, enabled) }
                    }
                    Spacer(Modifier.height(6.dp))
                }
                OutlinedTextField(
                    value = value,
                    onValueChange = onValueChange,
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Message LabSage...", color = scheme.onSurfaceVariant) },
                    maxLines = 5,
                    minLines = 1,
                    shape = RoundedCornerShape(22.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = scheme.primary,
                        unfocusedBorderColor = scheme.outlineVariant,
                        focusedContainerColor = scheme.surface,
                        unfocusedContainerColor = scheme.surfaceVariant
                    ),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(
                        onSend = { if (enabled && value.isNotBlank()) onSend() }
                    )
                )
            }
            Spacer(Modifier.width(8.dp))
            VoiceInputButton(
                onResult = onVoiceResult,
                enabled = enabled,
                modifier = Modifier
            )
            Spacer(Modifier.width(8.dp))
            FilledIconButton(
                onClick = onSend,
                enabled = enabled && value.isNotBlank(),
                modifier = Modifier.size(48.dp),
                shape = RoundedCornerShape(16.dp),
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = scheme.primary,
                    disabledContainerColor = scheme.outlineVariant
                )
            ) {
                Icon(Icons.AutoMirrored.Filled.Send, "Send", tint = Color.White)
            }
        }
    }
}

@Composable
private fun QuickChip(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit, enabled: Boolean) {
    val scheme = MaterialTheme.colorScheme

    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(16.dp),
        color = scheme.primaryContainer,
        tonalElevation = 0.dp
    ) {
        Row(
            Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, null, Modifier.size(14.dp), tint = scheme.primary)
            Spacer(Modifier.width(4.dp))
            Text(label, style = MaterialTheme.typography.labelSmall, color = scheme.primary)
        }
    }
}
