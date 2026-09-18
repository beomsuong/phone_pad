package com.example.phone_pad_app.data.repository

import com.example.phone_pad_app.data.network.TcpClient
import com.example.phone_pad_app.data.network.UdpClient
import com.example.phone_pad_app.di.IoDispatcher
import com.example.phone_pad_app.domain.model.ConnectionState
import com.example.phone_pad_app.domain.model.TrackpadEvent
import com.example.phone_pad_app.domain.repository.TrackpadRepository
import com.example.phone_pad_app.presentation.util.GestureConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 채널 분기 원칙 (AGENTS.md 섹션 4):
 * - [TrackpadEvent.Move] → UDP 9001, 세션 토큰 포함
 * - 그 외 이벤트 → TCP 9000, newline-delimited JSON
 *
 * 연결 유지(heartbeat, AGENTS.md 섹션 4/6):
 * - sender 루프가 [GestureConfig.HEARTBEAT_INTERVAL_MS]마다 `{"type":"HEARTBEAT"}`를 TCP로 보낸다.
 * - watchdog 루프가 TCP를 한 줄씩 읽으며 카운터 기반으로 연결 해제를 판정한다
 *   (타임아웃 +1 / 아무 줄이나 수신 시 0으로 리셋 / [GestureConfig.HEARTBEAT_MISS_LIMIT] 도달 시 Error).
 */
@Singleton
class TrackpadRepositoryImpl @Inject constructor(
    private val tcpClient: TcpClient,
    private val udpClient: UdpClient,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : TrackpadRepository {

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    override val connectionState: Flow<ConnectionState> = _connectionState

    /** TCP 핸드셰이크로 확보한 세션 토큰. UDP MOVE 패킷 인증에 사용한다. */
    @Volatile
    private var sessionToken: String? = null

    /** heartbeat sender/watchdog 루프를 담는 스코프. 연결 1회당 하나. */
    @Volatile
    private var keepAliveScope: CoroutineScope? = null

    /**
     * connect()/disconnect()를 직렬화한다 (F-3).
     * 이게 없으면 겹친 connect() 호출(예: 빠른 이중 탭) 중 먼저 시작한 호출이 나중에
     * 완료되면서 살아있는 새 연결을 덮어쓰거나(Connected인데 sender/watchdog이 없는 상태)
     * 소켓을 잘못 닫을 수 있다.
     */
    private val connectionMutex = Mutex()

    /**
     * 연결 세대(generation). connect()/disconnect()마다 +1 된다.
     * 루프는 시작 시점의 세대를 캡처하고, 상태를 바꾸기 전에 현재 세대와 같은지 확인한다.
     * 블로킹 읽기에 걸려 즉시 취소되지 않은 옛 루프가 새 연결을 훼손하지 못하게 하는 장치
     * (F-2 재연결 무효화 패턴과 동일한 정신).
     */
    private val generation = AtomicInteger(0)

    override suspend fun connect(host: String, port: Int) = connectionMutex.withLock {
        _connectionState.value = ConnectionState.Connecting
        // 재연결 시 이전 세션/UDP 타깃/heartbeat 루프가 새 핸드셰이크 완료 전까지
        // 남아있지 않도록 즉시 무효화한다.
        val currentGeneration = invalidateCurrentConnection()
        sessionToken = null
        runCatching { udpClient.close() }
        try {
            val session = tcpClient.connect(host, port)
            if (session.isNullOrBlank()) {
                cleanUp()
                _connectionState.value = ConnectionState.Error("Session handshake failed")
                return@withLock
            }
            sessionToken = session
            udpClient.connect(host, GestureConfig.UDP_PORT)
            _connectionState.value = ConnectionState.Connected(host)
            startKeepAlive(currentGeneration)
        } catch (e: Exception) {
            cleanUp()
            _connectionState.value = ConnectionState.Error(e.message ?: "Connection failed")
        }
    }

    override suspend fun sendEvent(event: TrackpadEvent) {
        when (event) {
            is TrackpadEvent.Move -> {
                // 세션 토큰이 없으면 서버가 어차피 패킷을 무시하므로 전송하지 않는다.
                // MOVE는 고빈도 이벤트여서 연결 상태를 Error로 덮어쓰지 않는다(F-2) —
                // 실패는 조용히 버린다. 실제 연결 유실은 heartbeat watchdog이 이미 감지해
                // Error로 전환하므로, 여기서 상태를 덮어쓰면 그 원인 메시지만 지워진다.
                val session = sessionToken ?: return
                runCatching {
                    udpClient.send(
                        """{"session":"$session","type":"MOVE","dx":${event.dx},"dy":${event.dy}}"""
                    )
                }
            }

            is TrackpadEvent.Click -> {
                try {
                    tcpClient.send("""{"type":"CLICK","button":"${event.button}"}""")
                } catch (e: Exception) {
                    _connectionState.value = ConnectionState.Error(e.message ?: "Send failed")
                }
            }

            is TrackpadEvent.DoubleClick -> {
                // 이동 좌표가 아니므로 TCP (session 필드 없음, AGENTS.md 섹션 4).
                // CLICK과 같은 등급의 저빈도 · 사용자 명시 행동이라 전송 실패를 조용히 버리지
                // 않고 Error로 알린다 — MOVE/SCROLL처럼 초당 수십 번 나가는 이벤트가 아니어서
                // watchdog이 세팅한 원인 메시지를 덮어쓸 위험이 사실상 없다.
                try {
                    tcpClient.send("""{"type":"DOUBLE_CLICK","button":"${event.button}"}""")
                } catch (e: Exception) {
                    _connectionState.value = ConnectionState.Error(e.message ?: "Send failed")
                }
            }

            is TrackpadEvent.Scroll -> {
                // 이동 좌표가 아니므로 TCP. dx/dy는 이미 정수 스텝이라 CLICK과 같은 평문 이벤트로
                // 보낸다 (session 필드 없음, AGENTS.md 섹션 4).
                // MOVE와 마찬가지로 고빈도 이벤트라 실패해도 연결 상태를 덮어쓰지 않는다(F-1) —
                // 드래그 도중 큐에 남은 SCROLL 전송 실패가 heartbeat watchdog이 이미 세팅한
                // "Heartbeat timeout" 같은 원인 메시지를 지워버리는 것을 막는다.
                runCatching {
                    tcpClient.send("""{"type":"SCROLL","dx":${event.dx},"dy":${event.dy}}""")
                }
            }
        }
    }

    override suspend fun disconnect() = connectionMutex.withLock {
        invalidateCurrentConnection()
        cleanUp()
        _connectionState.value = ConnectionState.Disconnected
    }

    // --- heartbeat ------------------------------------------------------------------

    /**
     * 이전 연결의 루프를 취소하고 세대를 올린다.
     * @return 새로 시작할 연결이 사용할 세대 번호
     */
    private fun invalidateCurrentConnection(): Int {
        val next = generation.incrementAndGet()
        stopKeepAlive()
        return next
    }

    private fun stopKeepAlive() {
        val scope = keepAliveScope
        keepAliveScope = null
        runCatching { scope?.cancel() }
    }

    private fun startKeepAlive(forGeneration: Int) {
        val scope = CoroutineScope(SupervisorJob() + ioDispatcher)
        keepAliveScope = scope
        scope.launch { heartbeatSenderLoop(forGeneration) }
        scope.launch { heartbeatWatchdogLoop(forGeneration) }
    }

    /** [GestureConfig.HEARTBEAT_INTERVAL_MS]마다 HEARTBEAT 한 줄을 보낸다. 첫 전송은 1주기 뒤. */
    private suspend fun heartbeatSenderLoop(forGeneration: Int) {
        while (currentCoroutineContext().isActive && forGeneration == generation.get()) {
            delay(GestureConfig.HEARTBEAT_INTERVAL_MS)
            if (forGeneration != generation.get()) return
            try {
                tcpClient.send(HEARTBEAT_JSON)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 전송 자체가 실패하면 소켓이 이미 죽은 것이므로 즉시 연결 해제로 판정한다.
                reportConnectionLost(forGeneration, MESSAGE_CONNECTION_LOST)
                return
            }
        }
    }

    /**
     * 카운터 기반 연결 해제 판정 (확정 스펙):
     * - 읽기 타임아웃 → 미응답 +1, [GestureConfig.HEARTBEAT_MISS_LIMIT] 도달 시 "Heartbeat timeout"
     * - 아무 줄이나 성공 수신(HEARTBEAT_ACK 여부 무관) → 카운터 0으로 리셋
     * - EOF(null) 또는 기타 예외 → 카운터를 기다리지 않고 즉시 "Connection lost"
     */
    private suspend fun heartbeatWatchdogLoop(forGeneration: Int) {
        var missedBeats = 0
        while (currentCoroutineContext().isActive && forGeneration == generation.get()) {
            try {
                val line = tcpClient.readLine()
                if (line == null) {
                    reportConnectionLost(forGeneration, MESSAGE_CONNECTION_LOST)
                    return
                }
                missedBeats = 0
            } catch (e: CancellationException) {
                throw e
            } catch (e: SocketTimeoutException) {
                missedBeats += 1
                if (missedBeats >= GestureConfig.HEARTBEAT_MISS_LIMIT) {
                    reportConnectionLost(forGeneration, MESSAGE_HEARTBEAT_TIMEOUT)
                    return
                }
            } catch (e: Exception) {
                reportConnectionLost(forGeneration, MESSAGE_CONNECTION_LOST)
                return
            }
        }
    }

    /** 루프가 연결 해제를 판정했을 때만 호출. 세대가 어긋난(이미 무효화된) 루프는 아무것도 하지 않는다. */
    private fun reportConnectionLost(forGeneration: Int, message: String) {
        // 세대를 선점(CAS)한 루프만 상태를 바꾼다 — 두 루프가 동시에 실패를 보고하지 않게 한다.
        if (!generation.compareAndSet(forGeneration, forGeneration + 1)) return
        cleanUp()
        _connectionState.value = ConnectionState.Error(message)
        // 형제 루프도 함께 정리한다 (자기 자신이 속한 스코프이므로 호출 직후 return 한다).
        stopKeepAlive()
    }

    private fun cleanUp() {
        sessionToken = null
        runCatching { udpClient.close() }
        runCatching { tcpClient.disconnect() }
    }

    private companion object {
        const val HEARTBEAT_JSON = """{"type":"HEARTBEAT"}"""
        const val MESSAGE_HEARTBEAT_TIMEOUT = "Heartbeat timeout"
        const val MESSAGE_CONNECTION_LOST = "Connection lost"
    }
}
