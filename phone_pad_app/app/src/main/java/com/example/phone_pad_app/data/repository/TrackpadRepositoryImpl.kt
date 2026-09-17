package com.example.phone_pad_app.data.repository

import com.example.phone_pad_app.data.network.TcpClient
import com.example.phone_pad_app.data.network.UdpClient
import com.example.phone_pad_app.domain.model.ConnectionState
import com.example.phone_pad_app.domain.model.TrackpadEvent
import com.example.phone_pad_app.domain.repository.TrackpadRepository
import com.example.phone_pad_app.presentation.util.GestureConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 채널 분기 원칙 (AGENTS.md 섹션 4):
 * - [TrackpadEvent.Move] → UDP 9001, 세션 토큰 포함
 * - 그 외 이벤트 → TCP 9000, newline-delimited JSON
 */
@Singleton
class TrackpadRepositoryImpl @Inject constructor(
    private val tcpClient: TcpClient,
    private val udpClient: UdpClient,
) : TrackpadRepository {

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    override val connectionState: Flow<ConnectionState> = _connectionState

    /** TCP 핸드셰이크로 확보한 세션 토큰. UDP MOVE 패킷 인증에 사용한다. */
    @Volatile
    private var sessionToken: String? = null

    override suspend fun connect(host: String, port: Int) {
        _connectionState.value = ConnectionState.Connecting
        // 재연결 시 이전 세션/UDP 타깃이 새 핸드셰이크 완료 전까지 남아있지 않도록 즉시 무효화한다.
        sessionToken = null
        runCatching { udpClient.close() }
        try {
            val session = tcpClient.connect(host, port)
            if (session.isNullOrBlank()) {
                cleanUp()
                _connectionState.value = ConnectionState.Error("Session handshake failed")
                return
            }
            sessionToken = session
            udpClient.connect(host, GestureConfig.UDP_PORT)
            _connectionState.value = ConnectionState.Connected(host)
        } catch (e: Exception) {
            cleanUp()
            _connectionState.value = ConnectionState.Error(e.message ?: "Connection failed")
        }
    }

    override suspend fun sendEvent(event: TrackpadEvent) {
        try {
            when (event) {
                is TrackpadEvent.Move -> {
                    // 세션 토큰이 없으면 서버가 어차피 패킷을 무시하므로 전송하지 않는다.
                    // MOVE는 고빈도 이벤트여서 연결 상태를 Error로 덮어쓰지 않는다.
                    val session = sessionToken ?: return
                    udpClient.send(
                        """{"session":"$session","type":"MOVE","dx":${event.dx},"dy":${event.dy}}"""
                    )
                }

                is TrackpadEvent.Click -> {
                    tcpClient.send("""{"type":"CLICK","button":"${event.button}"}""")
                }
            }
        } catch (e: Exception) {
            _connectionState.value = ConnectionState.Error(e.message ?: "Send failed")
        }
    }

    override suspend fun disconnect() {
        cleanUp()
        _connectionState.value = ConnectionState.Disconnected
    }

    private fun cleanUp() {
        sessionToken = null
        runCatching { udpClient.close() }
        runCatching { tcpClient.disconnect() }
    }
}
