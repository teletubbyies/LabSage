package com.labsage.app.notification

/** Pure notification content policy, kept Android-free so it can be unit tested. */
object ResponseNotificationContent {
    const val MIN_LONG_RESPONSE_LENGTH = 300
    const val MAX_SUMMARY_LENGTH = 180

    fun isLongResponse(answer: String): Boolean =
        answer.count { !it.isWhitespace() } >= MIN_LONG_RESPONSE_LENGTH

    fun summary(answer: String): String {
        val plainText = answer
            .replace(Regex("```[a-zA-Z0-9_+-]*"), " ")
            .replace("```", " ")
            .replace(Regex("!\\[([^]]*)]\\([^)]*\\)"), "$1")
            .replace(Regex("\\[([^]]+)]\\([^)]*\\)"), "$1")
            .replace(Regex("(?m)^\\s{0,3}#{1,6}\\s*"), "")
            .replace(Regex("(?m)^\\s*[-*+>]\\s+"), "")
            .replace(Regex("[*_`~]"), "")
            .replace(Regex("\\s+"), " ")
            .trim()

        return when {
            plainText.length <= MAX_SUMMARY_LENGTH -> plainText
            else -> plainText.take(MAX_SUMMARY_LENGTH - 1).trimEnd() + "…"
        }
    }
}
