package com.example.phone_pad_app.presentation.trackpad

import com.example.phone_pad_app.domain.model.ConnectionState
import com.example.phone_pad_app.presentation.util.GestureConfig

data class TrackpadUiState(
    val connectionState: ConnectionState = ConnectionState.Disconnected,
    val hostInput: String = "",
    val port: Int = GestureConfig.DEFAULT_PORT,
)
