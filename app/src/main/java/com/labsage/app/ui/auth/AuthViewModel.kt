package com.labsage.app.ui.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.labsage.app.data.auth.AuthGateway
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class AuthMode { LOGIN, REGISTER }

data class AuthUiState(
    val mode: AuthMode = AuthMode.LOGIN,
    val isLoading: Boolean = false,
    val errorMessage: String? = null
)

class AuthViewModel(
    private val gateway: AuthGateway
) : ViewModel() {
    private val _uiState = MutableStateFlow(AuthUiState())
    val uiState: StateFlow<AuthUiState> = _uiState.asStateFlow()

    fun setMode(mode: AuthMode) {
        if (_uiState.value.isLoading) return
        _uiState.value = AuthUiState(mode = mode)
    }

    fun submit(username: String, password: String) {
        if (_uiState.value.isLoading) return
        if (username.trim().length !in 3..64 || password.toByteArray().size !in 8..72) {
            _uiState.value = _uiState.value.copy(
                errorMessage = "用户名需为 3–64 个字符，密码需为 8–72 个字节"
            )
            return
        }
        viewModelScope.launch {
            val mode = _uiState.value.mode
            _uiState.value = _uiState.value.copy(isLoading = true, errorMessage = null)
            val result = when (mode) {
                AuthMode.LOGIN -> gateway.login(username, password)
                AuthMode.REGISTER -> gateway.register(username, password)
            }
            _uiState.value = _uiState.value.copy(
                isLoading = false,
                errorMessage = result.exceptionOrNull()?.message
            )
        }
    }
}
