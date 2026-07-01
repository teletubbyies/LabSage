package com.labsage.app.ui.chat.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.labsage.app.ui.theme.*

@Composable
fun PartialMessageBanner(
    errorMessage: String,
    onContinue: () -> Unit,
    onRegenerate: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        shape = RoundedCornerShape(12.dp),
        color = ErrorContainer
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(Modifier.weight(1f).padding(end = 8.dp)) {
                Text("Generation interrupted", style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold, color = Error))
                if (errorMessage.isNotBlank()) Text(errorMessage, style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp), color = TextSecondary)
            }
            Row {
                OutlinedButton(onClick = onRegenerate, shape = RoundedCornerShape(8.dp), contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp)) {
                    Text("Regenerate", style = MaterialTheme.typography.labelSmall)
                }
                Spacer(Modifier.width(8.dp))
                Button(onClick = onContinue, shape = RoundedCornerShape(8.dp), colors = ButtonDefaults.buttonColors(containerColor = Primary), contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp)) {
                    Text("Continue", style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium))
                }
            }
        }
    }
}
