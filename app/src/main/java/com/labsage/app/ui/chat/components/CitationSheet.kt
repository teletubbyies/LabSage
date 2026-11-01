package com.labsage.app.ui.chat.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Tag
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.labsage.app.domain.model.MessageReference

/**
 * Bottom-sheet body showing one citation's chunk metadata.
 * Hosted by ChatScreen inside a ModalBottomSheet; purely presentational.
 */
@Composable
fun CitationDetailSheetContent(
    citation: MessageReference,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp)
            .navigationBarsPadding()
            .padding(bottom = 24.dp)
    ) {
        // 引用编号徽标
        Surface(
            shape = RoundedCornerShape(50),
            color = MaterialTheme.colorScheme.secondaryContainer
        ) {
            Text(
                text = "引用 ${citation.ref}",
                style = MaterialTheme.typography.labelMedium.copy(
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                ),
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
            )
        }
        Spacer(Modifier.height(12.dp))

        // 标题
        if (citation.title.isNotBlank()) {
            Text(
                text = citation.title,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
            )
            Spacer(Modifier.height(10.dp))
        }

        // 来源文件
        MetaRow(Icons.Default.Description, "来源", citation.source.ifBlank { "—" })

        Spacer(Modifier.height(6.dp))
        // chunk id（知识库稳定标识）
        MetaRow(Icons.Default.Tag, "chunk", citation.chunkId.ifBlank { "—" }, mono = true)

        Spacer(Modifier.height(6.dp))
        // 内容指纹：展示前 12 位即可核对版本
        MetaRow(
            Icons.Default.Fingerprint,
            "指纹",
            citation.contentHash.take(12).ifBlank { "—" },
            mono = true
        )

        // 片段正文预览
        if (!citation.preview.isNullOrBlank()) {
            Spacer(Modifier.height(14.dp))
            HorizontalDivider()
            Spacer(Modifier.height(14.dp))
            Text(
                text = citation.preview,
                style = MaterialTheme.typography.bodySmall.copy(lineHeight = MaterialTheme.typography.bodySmall.lineHeight * 1.3)
            )
        }
    }
}

@Composable
private fun MetaRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    value: String,
    mono: Boolean = false
) {
    Row(verticalAlignment = Alignment.Top) {
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(15.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium.copy(
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = value,
            style = if (mono) {
                MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
            } else {
                MaterialTheme.typography.bodySmall
            }
        )
    }
}
