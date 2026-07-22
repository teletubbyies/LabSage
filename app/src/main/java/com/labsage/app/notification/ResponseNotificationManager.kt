package com.labsage.app.notification

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.labsage.app.MainActivity
import com.labsage.app.R

class ResponseNotificationManager(private val context: Context) {

    fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val channel = NotificationChannel(
            CHANNEL_ID,
            "长回答完成",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "LabSage 长回答生成完成提醒"
        }
        context.getSystemService(NotificationManager::class.java)
            .createNotificationChannel(channel)
    }

    fun notifyResponseCompleted(
        conversationId: String,
        messageId: String,
        answer: String
    ) {
        if (!ResponseNotificationContent.isLongResponse(answer) || !canPostNotifications()) return

        val summary = ResponseNotificationContent.summary(answer)
            .ifBlank { "回答已生成，点击查看完整内容" }
        val openResponseIntent = Intent(context, MainActivity::class.java).apply {
            action = ACTION_OPEN_RESPONSE
            putExtra(EXTRA_CONVERSATION_ID, conversationId)
            putExtra(EXTRA_MESSAGE_ID, messageId)
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            messageId.hashCode(),
            openResponseIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("长回答已生成")
            .setContentText(summary)
            .setStyle(NotificationCompat.BigTextStyle().bigText(summary))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        try {
            NotificationManagerCompat.from(context)
                .notify(NOTIFICATION_TAG, messageId.hashCode(), notification)
        } catch (_: SecurityException) {
            // Permission can be revoked between the check and notify(); completion
            // alerts are best-effort and must never break the response finalization.
        }
    }

    private fun canPostNotifications(): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
    }

    companion object {
        const val CHANNEL_ID = "long_response_completed"
        const val ACTION_OPEN_RESPONSE = "com.labsage.app.action.OPEN_RESPONSE"
        const val EXTRA_CONVERSATION_ID = "conversation_id"
        const val EXTRA_MESSAGE_ID = "message_id"
        private const val NOTIFICATION_TAG = "long-response"
    }
}
