package com.example.phone_pad_app.domain.model

sealed class ConnectionState {
    object Disconnected : ConnectionState()
    object Connecting : ConnectionState()
    data class Connected(val host: String) : ConnectionState()
    data class Error(val message: String) : ConnectionState()
}
