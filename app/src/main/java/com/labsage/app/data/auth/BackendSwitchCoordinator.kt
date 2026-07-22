package com.labsage.app.data.auth

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Runs credential revocation and backend persistence outside the Compose lifecycle. */
class BackendSwitchCoordinator(
    private val applicationScope: CoroutineScope,
    private val logout: suspend () -> Unit,
    private val persistBaseUrl: suspend (String) -> Unit
) {
    fun switchTo(baseUrl: String): Job = applicationScope.launch {
        logout()
        persistBaseUrl(baseUrl)
    }
}
