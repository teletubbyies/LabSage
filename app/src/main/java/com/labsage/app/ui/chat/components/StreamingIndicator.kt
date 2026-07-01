package com.labsage.app.ui.chat.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.labsage.app.ui.theme.Primary
import com.labsage.app.ui.theme.TextTertiary

@Composable
fun StreamingIndicator(modifier: Modifier = Modifier) {
    val infinite = rememberInfiniteTransition(label = "stream")
    Row(
        modifier.padding(horizontal = 16.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("Thinking", style = MaterialTheme.typography.labelSmall, color = TextTertiary)
        repeat(3) { i ->
            val alpha by infinite.animateFloat(
                0.2f, 1f, infiniteRepeatable(tween(400, delayMillis = i * 130), RepeatMode.Reverse), label = "d$i"
            )
            Box(Modifier.size(5.dp).clip(CircleShape).then(Modifier.background(Primary.copy(alpha = alpha))))
        }
    }
}
