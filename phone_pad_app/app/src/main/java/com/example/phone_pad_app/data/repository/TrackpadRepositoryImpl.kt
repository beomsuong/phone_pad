package com.example.phone_pad_app.data.repository

import com.example.phone_pad_app.data.network.TcpClient
import com.example.phone_pad_app.data.network.UdpClient
import com.example.phone_pad_app.di.IoDispatcher
import com.example.phone_pad_app.domain.model.ConnectionErrorClassifier
import com.example.phone_pad_app.domain.model.ConnectionErrorKind
import com.example.phone_pad_app.domain.model.ConnectionState
import com.example.phone_pad_app.domain.model.ReconnectPolicy
import com.example.phone_pad_app.domain.model.TrackpadEvent
import com.example.phone_pad_app.domain.repository.TrackpadRepository
import com.example.phone_pad_app.presentation.util.GestureConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
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
 *
 * 자동 재연결 (Phase 4):
 * - **한 번 Connected였던 세션이 유실된 경우에만** [ReconnectPolicy]에 따라 재시도한다.
 *   사용자가 처음 입력한 IP로의 connect() 실패는 기존대로 `Error`로 남긴다
 *   (틀린 IP에 55초씩 매달리지 않기 위해).
 * - 유실은 `Error`를 거치지 않고 곧바로 [ConnectionState.Reconnecting]으로 간다.
 */
@Singleton
class TrackpadRepositoryImpl @Inject constructor(
    private val tcpClient: TcpClient,
    private val udpClient: UdpClient,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    private val reconnectPolicy: ReconnectPolicy,
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

    /**
     * **수동** 연결 시도 묶음의 세대. [connect]/[cancelConnect]/[disconnect]가 올릴 때마다
     * 직전까지 진행 중이던 시도가 무효가 된다.
     *
     * [generation](연결 단위)·[reconnectEpoch](재연결 묶음 단위)와 별개로 두는 이유는 역할이
     * 다르기 때문이다. 이 값은 "사용자가 요청한 **이 접속 시도**가 아직 유효한가"만 답한다 —
     * 블로킹 `connect()`는 코루틴 취소로 풀리지 않아서, 취소 후에도 시도가 계속 살아 있다가
     * 뒤늦게 성공/실패한다. 그 뒤늦은 결과가 `Connected`/`Error`를 쓰지 못하게 막는 장치다.
     *
     * 자동 재연결 루프는 이 값을 **건드리지 않는다**. 재연결이 이 값을 올리면, 마침 락을
     * 기다리던 사용자의 수동 연결이 영문도 모르고 무효화된다.
     */
    private val connectEpoch = AtomicInteger(0)

    /**
     * 자동 재연결 루프를 담는 스코프. [keepAliveScope]와 **분리해야 한다** —
     * 유실을 보고하는 쪽(heartbeat 루프)이 곧바로 자기 스코프를 취소하므로,
     * 같은 스코프에서 재연결을 시작하면 태어나자마자 취소된다.
     */
    private val reconnectScope = CoroutineScope(SupervisorJob() + ioDispatcher)

    @Volatile
    private var reconnectJob: Job? = null

    /**
     * 재연결 시도 묶음의 세대. 수동 [connect]/[disconnect]가 올 때마다 +1 된다.
     * 재연결 루프는 시작 시점의 값을 캡처하고, 상태를 쓰거나 재접속을 하기 전에 매번 확인한다.
     *
     * [generation](연결 단위)과 별개로 두는 이유: 연결 세대는 재연결 시도가 성공할 때마다
     * 올라가므로 "이 재시도 묶음이 아직 유효한가"를 표현할 수 없다.
     * 취소만으로 충분하지 않은 것도 같은 이유다 — cancel() 직후 아직 취소를 확인하지 못한
     * 루프가 상태를 한 번 더 덮어쓸 수 있어, 상태 쓰기마다 이 값으로 선점 여부를 본다.
     */
    private val reconnectEpoch = AtomicInteger(0)

    /** 마지막으로 **연결에 성공한** 대상. 재연결은 이 값으로만 시도한다(실패 중인 값이 아님). */
    @Volatile
    private var lastConnectedHost: String? = null

    @Volatile
    private var lastConnectedPort: Int = GestureConfig.DEFAULT_PORT

    /**
     * 사용자가 직접 요청한 연결. **항상 진행 중인 자동 재연결을 이긴다.**
     *
     * 재연결 취소를 뮤텍스 **밖에서** 먼저 하는 것이 중요하다 — 재연결 루프가 접속 시도 중
     * 뮤텍스를 쥐고 있을 수 있어서, 락을 잡은 뒤에 취소하려 하면 그 시도가 끝날 때까지
     * 기다리게 되고(= 이중 접속) 취소 의미도 사라진다.
     */
    override suspend fun connect(host: String, port: Int) {
        cancelReconnect()
        val epoch = connectEpoch.incrementAndGet()
        connectionMutex.withLock {
            // 락을 기다리는 동안 사용자가 취소했거나 다른 연결이 끼어들었을 수 있다.
            if (connectEpoch.get() != epoch) return
            _connectionState.value = ConnectionState.Connecting
            when (val outcome = openConnection(host, port) { connectEpoch.get() == epoch }) {
                // 취소된 시도의 뒤늦은 결과는 아무 상태도 쓰지 않는다 —
                // cancelConnect()가 이미 Disconnected로 돌려놨다.
                ConnectOutcome.Cancelled, ConnectOutcome.Success -> Unit
                // 첫 연결(또는 수동 연결) 실패는 재시도하지 않는다 — 틀린 IP에 무한히 매달리면 안 된다.
                is ConnectOutcome.Failure -> if (connectEpoch.get() == epoch) {
                    _connectionState.value = ConnectionState.Error(outcome.message, outcome.kind)
                }
            }
        }
    }

    /**
     * 진행 중인 첫 연결 시도를 취소한다 (`Connecting` 상태 전용).
     *
     * 순서가 설계의 전부다 — 전부 **[connectionMutex] 밖**에서 한다. 접속을 시도 중인
     * 코루틴이 락을 쥔 채 블로킹 `connect()`에 매달려 있으므로, 락을 먼저 잡으려 하면
     * 그 시도가 OS 타임아웃까지 끝나기를 기다리게 되어 "취소"가 취소가 아니게 된다.
     *
     * 1. [connectEpoch]를 올려 **진행 중인 시도를 먼저 무효화**한다. 이후 그 시도가 성공해도
     *    `Connected`를 쓰지 못하고, 실패해도 `Error`를 쓰지 못한다.
     * 2. 상태를 곧바로 [ConnectionState.Disconnected]로 돌린다(오류 표시 없음).
     * 3. 소켓을 닫아 블로킹 `connect()`/`readLine()`을 깨운다. 코루틴 취소로는 풀리지 않는
     *    블로킹 호출이라 소켓을 닫는 것이 유일한 수단이다.
     *
     * `Connecting`이 아니면 아무것도 하지 않는다 — 마침 연결에 성공한 순간 눌린 취소가
     * 살아있는 연결을 끊어버리는 일이 없도록, 그리고 재연결 취소([disconnect])와 역할이
     * 섞이지 않도록 한다.
     */
    override suspend fun cancelConnect() {
        if (_connectionState.value !is ConnectionState.Connecting) return
        connectEpoch.incrementAndGet()
        _connectionState.value = ConnectionState.Disconnected
        cleanUp()
    }

    /**
     * 실제 연결 1회: TCP 연결 + 핸드셰이크 → 세션 토큰 → UDP 타깃 → [ConnectionState.Connected]
     * → keep-alive 시작. **[connectionMutex]를 쥔 채로** 호출해야 한다.
     *
     * 수동 연결과 자동 재연결이 **같은 코드**를 타도록 여기로 추출했다. 실패 시 어떤 상태로
     * 갈지는 호출자가 정한다(수동 → `Error`, 재연결 → 다음 시도 또는 최종 `Error`).
     *
     * @param isStillWanted 이 시도가 아직 유효한지 묻는다. 호출자마다 근거가 다르다 —
     *   수동 연결은 [connectEpoch], 자동 재연결은 [reconnectEpoch]. 블로킹 접속이 끝난
     *   **직후**에 확인해서, 취소된 시도가 `Connected`를 써버리는 것을 막는다.
     */
    private suspend fun openConnection(
        host: String,
        port: Int,
        isStillWanted: () -> Boolean,
    ): ConnectOutcome {
        // 이전 세션/UDP 타깃/heartbeat 루프가 새 핸드셰이크 완료 전까지 남아있지 않도록 즉시 무효화한다.
        val currentGeneration = invalidateCurrentConnection()
        sessionToken = null
        runCatching { udpClient.close() }
        return try {
            val session = tcpClient.connect(host, port)
            if (!isStillWanted()) {
                // 취소와 성공이 겹친 경우. 붙어버린 소켓을 반드시 닫는다 —
                // 안 닫으면 서버에 유령 세션이 남고 앱은 그 사실을 영영 모른다.
                cleanUp()
                ConnectOutcome.Cancelled
            } else if (session.isNullOrBlank()) {
                cleanUp()
                ConnectOutcome.Failure(MESSAGE_HANDSHAKE_FAILED, ConnectionErrorKind.HANDSHAKE_FAILED)
            } else {
                sessionToken = session
                udpClient.connect(host, GestureConfig.UDP_PORT)
                lastConnectedHost = host
                lastConnectedPort = port
                _connectionState.value = ConnectionState.Connected(host)
                startKeepAlive(currentGeneration)
                ConnectOutcome.Success
            }
        } catch (e: CancellationException) {
            // 수동 connect()/disconnect()가 이 시도를 취소한 경우다. 여기서 상태를 건드리면
            // 사용자를 이긴 셈이 되므로 그대로 올린다 — 소켓 정리는 우리를 취소한 쪽이
            // (cleanUp() 또는 TcpClient.connect()의 선행 disconnect()로) 이어서 수행한다.
            throw e
        } catch (e: Exception) {
            cleanUp()
            if (!isStillWanted()) {
                // 취소가 소켓을 닫아 이 예외를 만든 경우가 대부분이다. 오류로 보고하지 않는다.
                ConnectOutcome.Cancelled
            } else {
                ConnectOutcome.Failure(
                    e.message ?: MESSAGE_CONNECTION_FAILED,
                    ConnectionErrorClassifier.classify(e),
                )
            }
        }
    }

    /** [openConnection] 한 번의 결과. 실패와 **취소**를 구분하는 것이 요점이다. */
    private sealed interface ConnectOutcome {
        object Success : ConnectOutcome
        object Cancelled : ConnectOutcome
        data class Failure(val message: String, val kind: ConnectionErrorKind) : ConnectOutcome
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
                sendOverTcp("""{"type":"CLICK","button":"${event.button}"}""")
            }

            is TrackpadEvent.DoubleClick -> {
                // 이동 좌표가 아니므로 TCP (session 필드 없음, AGENTS.md 섹션 4).
                // CLICK과 같은 등급의 저빈도 · 사용자 명시 행동이라 전송 실패를 조용히 버리지
                // 않고 Error로 알린다 — MOVE/SCROLL처럼 초당 수십 번 나가는 이벤트가 아니어서
                // watchdog이 세팅한 원인 메시지를 덮어쓸 위험이 사실상 없다.
                sendOverTcp("""{"type":"DOUBLE_CLICK","button":"${event.button}"}""")
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

            is TrackpadEvent.DragStart -> {
                // 필드가 전혀 없는 TCP 평문 이벤트 (session 없음, AGENTS.md 섹션 4).
                // CLICK과 같은 등급(저빈도 · 명시적 상태 전이)이라 실패를 조용히 버리지 않는다 —
                // 버튼 누름이 유실됐는데 사용자는 드래그가 되는 줄 알고 계속 움직이게 되므로,
                // 상태를 Error로 바꿔 즉시 드러내는 편이 낫다.
                sendOverTcp(DRAG_START_JSON)
            }

            is TrackpadEvent.DragEnd -> {
                // DRAG_END 유실은 PC 왼쪽 버튼이 눌린 채로 남는 최악의 상태를 만든다.
                // 전송 실패를 반드시 Error로 알린다 (서버도 연결 종료 시 강제 해제 안전장치를 가진다).
                sendOverTcp(DRAG_END_JSON)
            }

            is TrackpadEvent.DesktopSwitch -> {
                // 이동 좌표가 아니므로 TCP (session 필드 없음, AGENTS.md 섹션 4).
                // CLICK/DOUBLE_CLICK과 같은 등급(저빈도 · 사용자 명시 행동)이라 전송 실패를
                // 조용히 버리지 않고 reportConnectionLost로 합류시킨다 — 데스크톱이 안 넘어갔는데
                // 사용자가 이유를 모르는 상태로 남으면 안 된다.
                sendOverTcp("""{"type":"DESKTOP_SWITCH","direction":"${event.direction}"}""")
            }
        }
    }

    /**
     * 저빈도 · 사용자 명시 이벤트(CLICK/DOUBLE_CLICK/DRAG_START/DRAG_END)의 TCP 전송.
     *
     * 전송이 실패했다는 것은 소켓이 이미 죽었다는 뜻이므로, 상태만 `Error`로 바꾸고 마는 대신
     * heartbeat 루프의 유실 판정과 **완전히 같은 경로**([reportConnectionLost])로 합류시킨다.
     * 그렇지 않으면 소켓이 정리되지 않은 채 좀비 heartbeat 루프가 계속 돌다가 최대 5초 뒤
     * 같은 사실을 한 번 더 보고하고, 자동 재연결도 그만큼 늦게 시작된다.
     *
     * 세대는 **전송 시점의 값**을 캡처한다 — 그 사이에 이미 다른 경로가 유실을 보고했거나
     * 사용자가 재연결했다면 CAS가 실패해 이 보고는 조용히 버려진다(이중 보고 방지).
     *
     * MOVE/SCROLL은 여기로 오지 않는다(고빈도 이벤트 — F-1/F-2, 조용히 버리는 것이 의도).
     */
    private suspend fun sendOverTcp(json: String) {
        val forGeneration = generation.get()
        try {
            tcpClient.send(json)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            reportConnectionLost(forGeneration, e.message ?: MESSAGE_SEND_FAILED)
        }
    }

    override suspend fun disconnect() {
        // 사용자 조작이 항상 이긴다: 대기 중이든 접속 시도 중이든 재연결을 먼저 끊는다.
        cancelReconnect()
        // 진행 중인 수동 접속 시도도 무효화한다 — 락을 잡기 전에 해야 그 시도가 뒤늦게
        // 성공해 Disconnected를 Connected로 되살리는 일이 없다.
        connectEpoch.incrementAndGet()
        connectionMutex.withLock {
            invalidateCurrentConnection()
            cleanUp()
            _connectionState.value = ConnectionState.Disconnected
        }
    }

    // --- 자동 재연결 ------------------------------------------------------------------

    /** 진행 중인 재연결 시도 묶음을 무효화하고 취소한다. 수동 [connect]/[disconnect] 전용. */
    private fun cancelReconnect() {
        reconnectEpoch.incrementAndGet()
        val job = reconnectJob
        reconnectJob = null
        runCatching { job?.cancel() }
    }

    /**
     * 유실된 연결에 대해 백오프 재시도 묶음을 시작한다.
     *
     * 첫 [ConnectionState.Reconnecting]은 **이 함수 안에서 동기적으로** 쓴다 —
     * 코루틴이 스케줄될 때까지 기다리면 그 사이 UI가 이전 상태(Connected)를 붙들고 있거나
     * 다른 경로가 `Error`를 밀어넣을 여지가 생긴다.
     */
    private fun startReconnect(host: String, port: Int, initialMessage: String) {
        val policy = reconnectPolicy
        val epoch = reconnectEpoch.incrementAndGet()
        runCatching { reconnectJob?.cancel() }

        _connectionState.value = ConnectionState.Reconnecting(host, 1, policy.maxAttempts)

        reconnectJob = reconnectScope.launch {
            var lastMessage = initialMessage
            var attempt = 1
            while (policy.shouldAttempt(attempt)) {
                if (reconnectEpoch.get() != epoch) return@launch
                _connectionState.value =
                    ConnectionState.Reconnecting(host, attempt, policy.maxAttempts)

                delay(policy.delayBeforeAttempt(attempt))
                if (reconnectEpoch.get() != epoch) return@launch

                val outcome = connectionMutex.withLock {
                    // 락을 기다리는 동안 수동 연결이 끼어들었을 수 있다.
                    if (reconnectEpoch.get() != epoch) return@launch
                    openConnection(host, port) { reconnectEpoch.get() == epoch }
                }
                when (outcome) {
                    // 성공: openConnection이 이미 Connected로 바꾸고 keep-alive를 켰다.
                    // 시도 카운터는 루프를 빠져나가며 사라지므로 다음 유실은 다시 1부터 시작한다.
                    ConnectOutcome.Success -> return@launch
                    // 이 재시도 묶음이 무효가 됐다(수동 연결/해제). 상태는 그쪽이 소유한다.
                    ConnectOutcome.Cancelled -> return@launch
                    is ConnectOutcome.Failure -> {
                        lastMessage = outcome.message
                        attempt += 1
                    }
                }
            }
            if (reconnectEpoch.get() != epoch) return@launch
            // 원인 종류는 마지막 실패가 아니라 "재연결 소진"으로 고정한다 — 사용자가 취할
            // 조치가 개별 실패 원인과 다르고, 마지막 원인은 원문(message)에 그대로 남는다.
            _connectionState.value = ConnectionState.Error(
                "$MESSAGE_RECONNECT_FAILED_PREFIX$lastMessage",
                ConnectionErrorKind.RECONNECT_FAILED,
            )
        }
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
                    reportConnectionLost(
                        forGeneration,
                        MESSAGE_HEARTBEAT_TIMEOUT,
                        ConnectionErrorKind.HEARTBEAT_TIMEOUT,
                    )
                    return
                }
            } catch (e: Exception) {
                reportConnectionLost(forGeneration, MESSAGE_CONNECTION_LOST)
                return
            }
        }
    }

    /**
     * 연결 유실을 단 한 번만 처리하는 공통 지점.
     *
     * heartbeat sender/watchdog뿐 아니라 TCP 이벤트 전송 실패([sendOverTcp])도 여기로 온다.
     * 세대를 선점(CAS)한 보고자만 실제 처리를 한다 — 여러 경로가 같은 유실을 동시에
     * 보고하거나, 이미 무효화된(재연결·수동 조작으로 세대가 지난) 루프가 살아있는 연결을
     * 훼손하는 것을 막는다.
     *
     * 재연결 조건을 만족하면 `Error`를 **한 프레임도 거치지 않고** 곧바로
     * [ConnectionState.Reconnecting]으로 간다.
     */
    private fun reportConnectionLost(
        forGeneration: Int,
        message: String,
        kind: ConnectionErrorKind = ConnectionErrorKind.CONNECTION_LOST,
    ) {
        // **Connected였던 세션의 유실만** 처리한다. 정상적인 보고자(heartbeat 루프, 연결 중
        // 발생한 TCP 전송 실패)는 정의상 이 조건을 만족하며, 이 한 줄이 두 가지 사고를 막는다:
        //  - 수동 disconnect() 직후 뒤늦게 실패한 이벤트 전송이 연결을 되살리는 것
        //  - 재연결 진행 중(Reconnecting)에 도착한 좀비 전송 실패가 재시도를 Error로 깨는 것
        // (세대 CAS가 실제 중재자이고 이 검사는 보고 자격을 좁히는 역할이다.)
        if (_connectionState.value !is ConnectionState.Connected) return
        if (!generation.compareAndSet(forGeneration, forGeneration + 1)) return
        cleanUp()
        // 형제 루프도 함께 정리한다 (자기 자신이 속한 스코프일 수 있으므로 호출 직후 return 한다).
        stopKeepAlive()

        val host = lastConnectedHost
        if (reconnectPolicy.isActive && host != null) {
            startReconnect(host, lastConnectedPort, message)
        } else {
            _connectionState.value = ConnectionState.Error(message, kind)
        }
    }

    private fun cleanUp() {
        sessionToken = null
        runCatching { udpClient.close() }
        runCatching { tcpClient.disconnect() }
    }

    private companion object {
        const val HEARTBEAT_JSON = """{"type":"HEARTBEAT"}"""

        /** AGENTS.md 섹션 4의 와이어 포맷 — 필드 없음. 서버 `handle_event`와의 계약. */
        const val DRAG_START_JSON = """{"type":"DRAG_START"}"""
        const val DRAG_END_JSON = """{"type":"DRAG_END"}"""

        const val MESSAGE_HEARTBEAT_TIMEOUT = "Heartbeat timeout"
        const val MESSAGE_CONNECTION_LOST = "Connection lost"
        const val MESSAGE_HANDSHAKE_FAILED = "Session handshake failed"
        const val MESSAGE_CONNECTION_FAILED = "Connection failed"
        const val MESSAGE_SEND_FAILED = "Send failed"

        /** 재시도 횟수를 모두 소진했을 때의 최종 Error 접두사. 뒤에 마지막 실패 원인이 붙는다. */
        const val MESSAGE_RECONNECT_FAILED_PREFIX = "Reconnect failed: "
    }
}
