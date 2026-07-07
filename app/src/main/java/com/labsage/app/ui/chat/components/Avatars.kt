package com.labsage.app.ui.chat.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Science
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.labsage.app.ui.theme.brandBrush

/**
 * LabSage brand mark — a lab flask on the emerald→teal brand gradient.
 * Used for the assistant across the app (chat avatars, list headers, FAB).
 */
@Composable
fun BrandMark(
    modifier: Modifier = Modifier,
    size: Dp = 36.dp,
    icon: ImageVector = Icons.Filled.Science,
    iconTint: Color = Color.White
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(brandBrush()),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = iconTint,
            modifier = Modifier.size(size * 0.52f)
        )
    }
}

/** A neutral avatar for the human user (outlined style, no brand color). */
@Composable
fun UserAvatar(
    modifier: Modifier = Modifier,
    size: Dp = 36.dp
) {
    val scheme = MaterialTheme.colorScheme
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(scheme.secondaryContainer),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.Filled.Person,
            contentDescription = null,
            tint = scheme.onSecondaryContainer,
            modifier = Modifier.size(size * 0.55f)
        )
    }
}
