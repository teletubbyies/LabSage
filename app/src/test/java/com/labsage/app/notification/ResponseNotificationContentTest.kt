package com.labsage.app.notification

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ResponseNotificationContentTest {

    @Test
    fun longResponse_ignoresWhitespaceWhenApplyingThreshold() {
        val visibleText = "答".repeat(ResponseNotificationContent.MIN_LONG_RESPONSE_LENGTH)

        assertTrue(ResponseNotificationContent.isLongResponse("  $visibleText\n"))
        assertFalse(ResponseNotificationContent.isLongResponse("答".repeat(299) + " \n "))
    }

    @Test
    fun summary_removesCommonMarkdownAndCollapsesWhitespace() {
        val answer = """
            ## 结论
            - 使用 **NotificationChannel** 完成提醒
            - 点击 [通知](https://example.com) 返回会话
        """.trimIndent()

        assertEquals(
            "结论 使用 NotificationChannel 完成提醒 点击 通知 返回会话",
            ResponseNotificationContent.summary(answer)
        )
    }

    @Test
    fun summary_truncatesWithEllipsis() {
        val summary = ResponseNotificationContent.summary("A".repeat(500))

        assertEquals(ResponseNotificationContent.MAX_SUMMARY_LENGTH, summary.length)
        assertTrue(summary.endsWith("…"))
    }
}
