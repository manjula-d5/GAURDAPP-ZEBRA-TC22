package com.storelense.gateBt.ui.gate

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.storelense.gateBt.data.repository.GateRepository
import com.storelense.gateBt.data.repository.Result
import com.storelense.gateBt.data.remote.dto.GateCheckDto
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class DashboardState(
    val checks:    List<GateCheckDto> = emptyList(),
    val isLoading: Boolean            = false,
    val error:     String?            = null
) {
    val totalChecked:  Int get() = checks.size
    val totalReleased: Int get() = checks.count { it.outcome == "RELEASED" }
    val totalFlagged:  Int get() = checks.count { it.outcome == "FLAGGED" }
    val avgMatch: Float get() = if (checks.isEmpty()) 0f else
        checks.mapNotNull { if (it.expectedCount > 0) it.matchedCount.toFloat() / it.expectedCount else null }
            .average().toFloat()
}

@HiltViewModel
class DashboardViewModel @Inject constructor(
    private val gateRepo: GateRepository
) : ViewModel() {

    private val _state = MutableStateFlow(DashboardState())
    val state = _state.asStateFlow()

    init { load() }

    fun load() {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, error = null) }
            when (val r = gateRepo.getMyRecentChecks()) {
                is Result.Success -> _state.update { it.copy(isLoading = false, checks = r.data) }
                is Result.Error   -> _state.update { it.copy(isLoading = false, error = r.message) }
            }
        }
    }
}
