package com.example.phone_pad_app.data.repository

import com.example.phone_pad_app.data.network.TcpClient
import com.example.phone_pad_app.domain.model.ConnectionState
import com.example.phone_pad_app.domain.model.TrackpadEvent
import com.example.phone_pad_app.domain.repository.TrackpadRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TrackpadRepositoryImpl @Inject constructor(
    private val tcpClient: TcpClient
) : TrackpadRepository {

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    override val connectionState: Flow<ConnectionState> = _connectionState

    override suspend fun connect(host: String, port: Int) {
        _connectionState.value = ConnectionState.Connecting
        try {
            tcpClient.connect(host, port)
            _connectionState.value = ConnectionState.Connected(host)
        } catch (e: Exception) {
            _connectionState.value = ConnectionState.Error(e.message ?: "Connection failed")
        }
    }

    override suspend fun sendEvent(event: TrackpadEvent) {
        val json = when (event) {
            is TrackpadEvent.Move ->
                """{"type":"MOVE","dx":${event.dx},"dy":${event.dy}}"""
            is TrackpadEvent.Click ->
                """{"type":"CLICK","button":"${event.button}"}"""
        }
        try {
            tcpClient.send(json)
        } catch (e: Exception) {
            _connectionState.value = ConnectionState.Error(e.message ?: "Send failed")
        }
    }

    override suspend fun disconnect() {
        tcpClient.disconnect()
        _connectionState.value = ConnectionState.Disconnected
    }
}
