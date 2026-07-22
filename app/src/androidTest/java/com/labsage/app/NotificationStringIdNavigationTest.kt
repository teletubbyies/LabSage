package com.labsage.app

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.labsage.app.notification.ResponseNotificationManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class NotificationStringIdNavigationTest {
    @Test
    fun notificationStringMessageIdSurvivesIntentAndRoute() {
        val conversationId = UUID.randomUUID().toString()
        val messageId = UUID.randomUUID().toString()
        val destination = Intent().apply {
            action = ResponseNotificationManager.ACTION_OPEN_RESPONSE
            putExtra(ResponseNotificationManager.EXTRA_CONVERSATION_ID, conversationId)
            putExtra(ResponseNotificationManager.EXTRA_MESSAGE_ID, messageId)
        }.notificationDestination()

        assertEquals(NotificationDestination(conversationId, messageId), destination)
        assertEquals("chat/$conversationId?messageId=$messageId", NavRoutes.chatRoute(conversationId, messageId))
    }

    @Test
    fun legacyLongMessageExtraIsRejectedInsteadOfMisrouting() {
        val intent = Intent().apply {
            action = ResponseNotificationManager.ACTION_OPEN_RESPONSE
            putExtra(ResponseNotificationManager.EXTRA_CONVERSATION_ID, UUID.randomUUID().toString())
            putExtra(ResponseNotificationManager.EXTRA_MESSAGE_ID, 42L)
        }
        assertNull(intent.notificationDestination())
    }
}
