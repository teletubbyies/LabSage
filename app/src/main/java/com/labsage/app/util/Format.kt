package com.labsage.app.util

/**
 * Human-readable byte size: B / KB / MB.
 *
 * Shared by the chat screen (upload confirmation message) and the file
 * management page. Keep thresholds in sync — intentionally stops at MB since
 * uploads in this app are documents, not large media.
 */
fun formatFileSize(bytes: Long): String {
    return when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        else -> "${"%.1f".format(bytes.toDouble() / (1024 * 1024))} MB"
    }
}
