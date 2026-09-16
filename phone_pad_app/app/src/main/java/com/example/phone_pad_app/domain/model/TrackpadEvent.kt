package com.example.phone_pad_app.domain.model

sealed class TrackpadEvent {
    data class Move(val dx: Float, val dy: Float) : TrackpadEvent()
    data class Click(val button: String = "left") : TrackpadEvent()
}
