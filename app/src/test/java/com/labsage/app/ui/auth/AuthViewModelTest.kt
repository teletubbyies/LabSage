package com.labsage.app.ui.auth

import com.labsage.app.data.auth.AuthGateway
import com.labsage.app.data.auth.AuthenticationState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AuthViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `invalid credentials are rejected before repository call`() {
        val gateway = FakeAuthGateway()
        val viewModel = AuthViewModel(gateway)

        viewModel.submit("ab", "short")

        assertEquals(0, gateway.loginCalls)
        assertFalse(viewModel.uiState.value.errorMessage.isNullOrBlank())
    }

    @Test
    fun `register mode calls register and clears loading`() = runTest(dispatcher) {
        val gateway = FakeAuthGateway()
        val viewModel = AuthViewModel(gateway)
        viewModel.setMode(AuthMode.REGISTER)

        viewModel.submit("alice", "password123")
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(1, gateway.registerCalls)
        assertFalse(viewModel.uiState.value.isLoading)
        assertEquals(null, viewModel.uiState.value.errorMessage)
    }

    @Test
    fun `repository failure is shown and allows retry`() = runTest(dispatcher) {
        val gateway = FakeAuthGateway().apply { result = Result.failure(IllegalStateException("认证失败")) }
        val viewModel = AuthViewModel(gateway)

        viewModel.submit("alice", "password123")
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals("认证失败", viewModel.uiState.value.errorMessage)
        assertFalse(viewModel.uiState.value.isLoading)
        assertTrue(gateway.loginCalls == 1)
    }
}
private class FakeAuthGateway : AuthGateway {
    override val state: StateFlow<AuthenticationState> = MutableStateFlow(AuthenticationState.Unauthenticated)
    var loginCalls = 0
    var registerCalls = 0
    var result: Result<Unit> = Result.success(Unit)

    override suspend fun login(username: String, password: String): Result<Unit> {
        loginCalls++
        return result
    }

    override suspend fun register(username: String, password: String): Result<Unit> {
        registerCalls++
        return result
    }

    override suspend fun logout() = Unit
}
