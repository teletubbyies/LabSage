package com.labsage.app.data.auth

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BackendSwitchCoordinatorTest {
    @Test
    fun `switch revokes old origin before persisting new base url`() = runTest {
        val events = mutableListOf<String>()
        val coordinator = BackendSwitchCoordinator(
            applicationScope = this,
            logout = { events += "logout" },
            persistBaseUrl = { events += "save:$it" }
        )

        coordinator.switchTo("https://new.example/").join()

        assertEquals(listOf("logout", "save:https://new.example/"), events)
    }
}
