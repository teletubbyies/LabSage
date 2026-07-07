package com.labsage.app.ui.chat.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.unit.dp
import com.labsage.app.ui.theme.LocalExtendedColors

@Composable
fun StreamingIndicator(modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val ext = LocalExtendedColors.current
    val infinite = rememberInfiniteTransition(label = "stream")

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 5.dp),
        horizontalArrangement = Arrangement.Start,
        verticalAlignment = Alignment.Bottom
    ) {
        BrandMark(size = 30.dp)
        Spacer(Modifier.width(8.dp))
        Surface(
            shape = RoundedCornerShape(
                topStart = 18.dp,
                topEnd = 18.dp,
                bottomStart = 5.dp,
                bottomEnd = 18.dp
            ),
            color = ext.assistantBubble
        ) {
            Row(
                Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                repeat(3) { i ->
                    val scale by infinite.animateFloat(
                        0.6f, 1f,
                        infiniteRepeatable(tween(500, delayMillis = i * 160), RepeatMode.Reverse),
                        label = "d$i"
                    )
                    val alpha by infinite.animateFloat(
                        0.3f, 1f,
                        infiniteRepeatable(tween(500, delayMillis = i * 160), RepeatMode.Reverse),
                        label = "da$i"
                    )
                    Box(
                        Modifier
                            .size(7.dp)
                            .scale(scale)
                            .clip(CircleShape)
                            .background(scheme.primary.copy(alpha = alpha))
                    )
                }
            }
        }
    }
}
