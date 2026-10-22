package com.storelense.gateBt.ui.login

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.storelense.gateBt.data.repository.AuthRepository
import com.storelense.gateBt.data.repository.Result
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class LoginState(
    val username:  String  = "",
    val password:  String  = "",
    val isLoading: Boolean = false,
    val error:     String? = null
)

@HiltViewModel
class LoginViewModel @Inject constructor(private val auth: AuthRepository) : ViewModel() {

    private val _state = MutableStateFlow(LoginState())
    val state = _state.asStateFlow()

    fun onUsername(v: String) = _state.update { it.copy(username = v, error = null) }
    fun onPassword(v: String) = _state.update { it.copy(password = v, error = null) }

    fun login(onSuccess: () -> Unit) {
        val s = _state.value
        if (s.username.isBlank() || s.password.isBlank()) {
            _state.update { it.copy(error = "Enter username and password") }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, error = null) }
            when (val r = auth.login(s.username, s.password)) {
                is Result.Success -> onSuccess()
                is Result.Error   -> _state.update { it.copy(isLoading = false, error = r.message) }
            }
        }
    }
}
