package ru.hznik.devicebridge.feature.home

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class HomeViewModel : ViewModel() {
    private val _uiState = MutableStateFlow(ServerSessionUiState())

    val uiState: StateFlow<ServerSessionUiState> = _uiState.asStateFlow()
}
