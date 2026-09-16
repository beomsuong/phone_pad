package com.example.phone_pad_app.domain.repository

import com.example.phone_pad_app.domain.model.ConnectionState
import com.example.phone_pad_app.domain.model.TrackpadEvent
import kotlinx.coroutines.flow.Flow

interface TrackpadRepository {
    val connectionState: Flow<ConnectionState>
    suspend fun connect(host: String, port: Int)
    suspend fun sendEvent(event: TrackpadEvent)
    suspend fun disconnect()
}
