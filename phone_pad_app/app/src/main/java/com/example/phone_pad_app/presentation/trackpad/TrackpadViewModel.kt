package com.example.phone_pad_app.presentation.trackpad

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.phone_pad_app.domain.model.TrackpadEvent
import com.example.phone_pad_app.domain.repository.TrackpadRepository
import com.example.phone_pad_app.domain.usecase.SendEventUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class TrackpadViewModel @Inject constructor(
    private val sendEventUseCase: SendEventUseCase,
    private val repository: TrackpadRepository,
) : ViewModel() {

    private val _hostInput = MutableStateFlow("")

    val uiState = combine(
        repository.connectionState,
        _hostInput,
    ) { connectionState, host ->
        TrackpadUiState(connectionState = connectionState, hostInput = host)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = TrackpadUiState(),
    )

    fun onHostInputChange(value: String) {
        _hostInput.value = value
    }

    fun connect() {
        val host = _hostInput.value.trim()
        if (host.isBlank()) return
        viewModelScope.launch {
            repository.connect(host, uiState.value.port)
        }
    }

    fun disconnect() {
        viewModelScope.launch {
            repository.disconnect()
        }
    }

    fun sendMove(dx: Float, dy: Float) {
        viewModelScope.launch {
            runCatching { sendEventUseCase(TrackpadEvent.Move(dx, dy)) }
        }
    }

    /** 1손가락 탭 → 좌클릭. `{"type":"CLICK","button":"left"}` (TCP) */
    fun sendClick() {
        viewModelScope.launch {
            runCatching { sendEventUseCase(TrackpadEvent.Click(MultiTouchGestureTracker.BUTTON_LEFT)) }
        }
    }

    /** 2손가락 탭 → 우클릭. `{"type":"CLICK","button":"right"}` (TCP) */
    fun sendRightClick() {
        viewModelScope.launch {
            runCatching { sendEventUseCase(TrackpadEvent.Click(MultiTouchGestureTracker.BUTTON_RIGHT)) }
        }
    }
}
