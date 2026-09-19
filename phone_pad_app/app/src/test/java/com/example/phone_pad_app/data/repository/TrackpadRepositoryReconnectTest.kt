package com.example.phone_pad_app.data.repository

import com.example.phone_pad_app.data.network.TcpClient
import com.example.phone_pad_app.data.network.UdpClient
import com.example.phone_pad_app.domain.model.ConnectionState
import com.example.phone_pad_app.domain.model.ReconnectPolicy
import com.example.phone_pad_app.domain.model.TrackpadEvent
import com.example.phone_pad_app.presentation.util.GestureConfig
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

private const val HOST = "192.168.0.10"
private const val OTHER_HOST = "192.168.0.99"
private const val PORT = GestureConfig.DEFAULT_PORT
private const val SESSION_A = "0123456789abcdef0123456789abcdef"
private const val SESSION_B = "ffffffffffffffffffffffffffffffff"
private const val LOSS_AT_MS = 1_000L

/**
 * 자동 재연결(Phase 4) 검증 — 전부 가상 시간이다(실제 sleep 없음).
 *
 * 연결 유실은 watchdog이 읽는 TCP 스트림의 EOF(`readLine() == null`)로 모사한다.
 * 실제 기기에서 서버가 죽거나 WiFi가 끊겼을 때 나타나는 가장 흔한 모양이고,
 * heartbeat 카운터(5초 × 3회)를 기다리지 않아 테스트 타임라인이 짧아진다.
 *
 * **각 테스트는 반드시 `repository.disconnect()`로 끝낸다.** `runTest`는 본문이 끝난 뒤
 * 가상 시간을 계속 진행시키는데, heartbeat 루프나 대기 중인 재연결 루프를 살려두면
 * 영원히 끝나지 않는 일(5초마다 전송 → 유실 → 재연결 → …)을 무한히 돌리게 되고,
 * MockK가 그 호출을 전부 기록하다 힙을 소진한다(실제로 OOM으로 관측됨).
 * 기존 heartbeat 테스트도 같은 이유로 disconnect나 Error 상태로 끝난다.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TrackpadRepositoryReconnectTest {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var tcpClient: TcpClient
    private lateinit var udpClient: UdpClient

    @Before
    fun setUp() {
        tcpClient = mockk(relaxed = true)
        udpClient = mockk(relaxed = true)
        coEvery { tcpClient.connect(HOST, PORT) } returns SESSION_A
        coEvery { tcpClient.connect(OTHER_HOST, PORT) } returns SESSION_B
        // 기본: 서버가 아무것도 보내지 않는 상태로 계속 매달려 있는 읽기(= 연결 유지)
        coEvery { tcpClient.readLine() } coAnswers { awaitCancellation() }
    }

    private fun repositoryWith(policy: ReconnectPolicy = ReconnectPolicy.Default) =
        TrackpadRepositoryImpl(tcpClient, udpClient, dispatcher, policy)

    /** [times]번만 EOF로 연결을 끊고, 그 뒤의 연결은 계속 유지되게 한다. */
    private fun stubConnectionLoss(times: Int, afterMs: Long = LOSS_AT_MS) {
        var remaining = times
        coEvery { tcpClient.readLine() } coAnswers {
            if (remaining > 0) {
                remaining -= 1
                delay(afterMs)
                null
            } else {
                awaitCancellation()
            }
        }
    }

    /** 연결 성공 → [LOSS_AT_MS] 뒤 유실까지 진행시킨다. 반환 시점의 상태는 첫 Reconnecting이다. */
    private suspend fun TestScope.connectThenLose(
        repository: TrackpadRepositoryImpl,
        losses: Int = 1,
    ) {
        stubConnectionLoss(losses)
        repository.connect(HOST, PORT)
        runCurrent()
        advanceTimeBy(LOSS_AT_MS)
        runCurrent()
    }

    // --- 유실 → Reconnecting 전이 ------------------------------------------------------

    @Test
    fun `연결 유실은 Error를 거치지 않고 곧바로 Reconnecting으로 간다`() = runTest(dispatcher) {
        val repository = repositoryWith()
        connectThenLose(repository)

        // 유실 처리(cleanUp + 상태 전이 + 재연결 시작)는 reportConnectionLost 안에서
        // 중간에 suspend 없이 한 번에 일어난다 — 그래서 Error가 한 프레임도 보이지 않는다.
        assertEquals(
            ConnectionState.Reconnecting(HOST, 1, GestureConfig.RECONNECT_MAX_ATTEMPTS),
            repository.connectionState.first(),
        )
        // 유실된 소켓은 그대로 두지 않는다 (heartbeat 유실 처리와 동일 경로)
        verify(atLeast = 1) { tcpClient.disconnect() }
        verify(atLeast = 1) { udpClient.close() }

        repository.disconnect()
    }

    @Test
    fun `첫 백오프만큼 기다린 뒤에야 재접속을 시도한다`() = runTest(dispatcher) {
        val repository = repositoryWith()
        connectThenLose(repository)
        clearMocks(tcpClient, answers = false, recordedCalls = true)

        advanceTimeBy(GestureConfig.RECONNECT_BASE_DELAY_MS - 1)
        runCurrent()
        coVerify(exactly = 0) { tcpClient.connect(any(), any()) }

        advanceTimeBy(1)
        runCurrent()
        coVerify(exactly = 1) { tcpClient.connect(HOST, PORT) }
        assertEquals(ConnectionState.Connected(HOST), repository.connectionState.first())

        repository.disconnect()
    }

    @Test
    fun `재연결에 성공하면 새 세션 토큰이 UDP MOVE에 쓰인다`() = runTest(dispatcher) {
        val repository = repositoryWith()
        connectThenLose(repository)

        // 서버가 재기동되어 새 토큰을 발급한 상황
        coEvery { tcpClient.connect(HOST, PORT) } returns SESSION_B
        advanceTimeBy(GestureConfig.RECONNECT_BASE_DELAY_MS)
        runCurrent()
        assertEquals(ConnectionState.Connected(HOST), repository.connectionState.first())

        clearMocks(udpClient, answers = false, recordedCalls = true)
        repository.sendEvent(TrackpadEvent.Move(1f, 2f))

        val json = slot<String>()
        coVerify(exactly = 1) { udpClient.send(capture(json)) }
        assertTrue("옛 세션 토큰이 남아 있으면 서버가 패킷을 버린다", json.captured.contains(SESSION_B))
        assertTrue(!json.captured.contains(SESSION_A))

        repository.disconnect()
    }

    @Test
    fun `재연결 성공 후 다시 유실되면 시도 카운터가 1부터 다시 시작한다`() = runTest(dispatcher) {
        val repository = repositoryWith()
        // 두 번 끊긴다: 첫 연결에서 한 번, 재연결로 붙은 뒤 또 한 번
        connectThenLose(repository, losses = 2)
        assertEquals(
            ConnectionState.Reconnecting(HOST, 1, GestureConfig.RECONNECT_MAX_ATTEMPTS),
            repository.connectionState.first(),
        )

        advanceTimeBy(GestureConfig.RECONNECT_BASE_DELAY_MS)
        runCurrent()
        assertEquals(ConnectionState.Connected(HOST), repository.connectionState.first())

        // 두 번째 유실 — attempt가 2가 아니라 다시 1이어야 한다
        advanceTimeBy(LOSS_AT_MS)
        runCurrent()
        assertEquals(
            ConnectionState.Reconnecting(HOST, 1, GestureConfig.RECONNECT_MAX_ATTEMPTS),
            repository.connectionState.first(),
        )

        repository.disconnect()
    }

    // --- 재시도 소진 --------------------------------------------------------------------

    @Test
    fun `연속 실패하면 attempt가 1부터 N까지 오르고 소진 후 Reconnect failed로 끝난다`() =
        runTest(dispatcher) {
            val policy = ReconnectPolicy(maxAttempts = 3)
            val repository = repositoryWith(policy)
            var connectCalls = 0
            coEvery { tcpClient.connect(HOST, PORT) } coAnswers {
                connectCalls += 1
                if (connectCalls == 1) SESSION_A else throw java.net.ConnectException("refused")
            }

            connectThenLose(repository)
            assertEquals(
                ConnectionState.Reconnecting(HOST, 1, 3),
                repository.connectionState.first(),
            )

            // 1회차 실패 → 2회차 대기
            advanceTimeBy(policy.delayBeforeAttempt(1))
            runCurrent()
            assertEquals(
                ConnectionState.Reconnecting(HOST, 2, 3),
                repository.connectionState.first(),
            )

            // 2회차 실패 → 3회차 대기
            advanceTimeBy(policy.delayBeforeAttempt(2))
            runCurrent()
            assertEquals(
                ConnectionState.Reconnecting(HOST, 3, 3),
                repository.connectionState.first(),
            )

            // 3회차 실패 → 소진
            advanceTimeBy(policy.delayBeforeAttempt(3))
            runCurrent()
            assertEquals(
                ConnectionState.Error("Reconnect failed: refused"),
                repository.connectionState.first(),
            )

            // 포기한 뒤에는 시간이 아무리 흘러도 더 이상 두드리지 않는다
            advanceTimeBy(600_000)
            runCurrent()
            assertEquals(4, connectCalls) // 최초 1 + 재시도 3
            assertEquals(
                ConnectionState.Error("Reconnect failed: refused"),
                repository.connectionState.first(),
            )

            repository.disconnect()
        }

    // --- 첫 연결 실패는 재시도하지 않는다 ------------------------------------------------

    @Test
    fun `첫 연결 실패는 재시도하지 않고 Error로 남는다`() = runTest(dispatcher) {
        val repository = repositoryWith()
        coEvery { tcpClient.connect(HOST, PORT) } throws java.net.ConnectException("refused")

        repository.connect(HOST, PORT)
        runCurrent()
        assertEquals(ConnectionState.Error("refused"), repository.connectionState.first())

        // 틀린 IP에 55초씩 매달리면 안 된다 — 가상 시간을 한참 진행해도 추가 시도가 없다
        advanceTimeBy(600_000)
        runCurrent()
        coVerify(exactly = 1) { tcpClient.connect(HOST, PORT) }
        assertEquals(ConnectionState.Error("refused"), repository.connectionState.first())
    }

    @Test
    fun `핸드셰이크 실패도 재시도하지 않는다`() = runTest(dispatcher) {
        val repository = repositoryWith()
        coEvery { tcpClient.connect(HOST, PORT) } returns null

        repository.connect(HOST, PORT)
        advanceTimeBy(600_000)
        runCurrent()

        coVerify(exactly = 1) { tcpClient.connect(HOST, PORT) }
        assertEquals(
            ConnectionState.Error("Session handshake failed"),
            repository.connectionState.first(),
        )
    }

    // --- 사용자 조작이 항상 이긴다 --------------------------------------------------------

    @Test
    fun `재연결 대기 중 disconnect하면 즉시 Disconnected가 되고 재시도가 멈춘다`() =
        runTest(dispatcher) {
            val repository = repositoryWith()
            connectThenLose(repository)
            clearMocks(tcpClient, answers = false, recordedCalls = true)

            repository.disconnect()
            runCurrent()
            assertEquals(ConnectionState.Disconnected, repository.connectionState.first())

            advanceTimeBy(600_000)
            runCurrent()
            coVerify(exactly = 0) { tcpClient.connect(any(), any()) }
            // 취소된 재연결 루프가 뒤늦게 Reconnecting/Error를 밀어넣지 않는다
            assertEquals(ConnectionState.Disconnected, repository.connectionState.first())
        }

    @Test
    fun `재연결 대기 중 수동 connect는 재연결을 취소하고 한 번만 연결한다`() = runTest(dispatcher) {
        val repository = repositoryWith()
        connectThenLose(repository)
        clearMocks(tcpClient, answers = false, recordedCalls = true)

        // 사용자가 기다리지 않고 다른 IP를 직접 입력했다
        repository.connect(OTHER_HOST, PORT)
        runCurrent()
        assertEquals(ConnectionState.Connected(OTHER_HOST), repository.connectionState.first())

        advanceTimeBy(600_000)
        runCurrent()
        // 이중 접속 금지: 옛 대상으로의 재연결은 한 번도 일어나지 않는다
        coVerify(exactly = 0) { tcpClient.connect(HOST, PORT) }
        coVerify(exactly = 1) { tcpClient.connect(OTHER_HOST, PORT) }
        assertEquals(ConnectionState.Connected(OTHER_HOST), repository.connectionState.first())

        repository.disconnect()
    }

    @Test
    fun `수동 disconnect 뒤의 전송 실패는 연결을 되살리지 않는다`() = runTest(dispatcher) {
        val repository = repositoryWith()
        repository.connect(HOST, PORT)
        runCurrent()
        repository.disconnect()
        runCurrent()
        clearMocks(tcpClient, answers = false, recordedCalls = true)
        coEvery { tcpClient.send(any()) } throws java.io.IOException("Not connected")

        // 화면이 아직 살아 있어 뒤늦게 도착한 지연 클릭 같은 경우
        repository.sendEvent(TrackpadEvent.Click("left"))
        advanceTimeBy(600_000)
        runCurrent()

        coVerify(exactly = 0) { tcpClient.connect(any(), any()) }
        assertEquals(ConnectionState.Disconnected, repository.connectionState.first())
    }

    // --- TCP 이벤트 전송 실패도 유실 처리에 합류한다 ----------------------------------------

    @Test
    fun `Click 전송 실패는 소켓을 정리하고 재연결을 시작한다`() = runTest(dispatcher) {
        // 백오프를 아주 길게 잡아(상한도 함께 올려야 클램프되지 않는다) "재시도 이전" 구간만 관찰한다.
        val repository = repositoryWith(
            ReconnectPolicy(maxAttempts = 1, baseDelayMs = 100_000, maxDelayMs = 100_000)
        )
        repository.connect(HOST, PORT)
        runCurrent()
        clearMocks(tcpClient, udpClient, answers = false, recordedCalls = true)
        coEvery { tcpClient.send(any()) } throws java.io.IOException("broken pipe")

        repository.sendEvent(TrackpadEvent.Click("left"))
        runCurrent()

        // 예전에는 상태만 Error로 바뀌고 소켓/루프가 그대로 남았다 — 이제 유실 경로로 합류한다
        assertEquals(
            ConnectionState.Reconnecting(HOST, 1, 1),
            repository.connectionState.first(),
        )
        verify(exactly = 1) { tcpClient.disconnect() }
        verify(exactly = 1) { udpClient.close() }

        // 세대 CAS 확인: 살아남은 옛 heartbeat sender가 5초 뒤 같은 실패를 다시 보고하면
        // cleanUp이 한 번 더 돌고 상태가 흔들린다. 그런 일이 없어야 한다.
        advanceTimeBy(GestureConfig.HEARTBEAT_INTERVAL_MS * 4)
        runCurrent()
        verify(exactly = 1) { tcpClient.disconnect() }
        assertEquals(
            ConnectionState.Reconnecting(HOST, 1, 1),
            repository.connectionState.first(),
        )

        repository.disconnect()
    }

    @Test
    fun `DragEnd 전송 실패도 재연결을 시작한다`() = runTest(dispatcher) {
        // DRAG_END 유실은 PC 버튼이 눌린 채 남는 최악의 상태 — 재연결로 복구 경로를 연다
        // (서버는 옛 연결이 끊긴 것을 감지하면 버튼을 강제로 놓는다).
        val repository = repositoryWith(
            ReconnectPolicy(maxAttempts = 1, baseDelayMs = 100_000, maxDelayMs = 100_000)
        )
        repository.connect(HOST, PORT)
        runCurrent()
        coEvery { tcpClient.send(any()) } throws java.io.IOException("broken pipe")

        repository.sendEvent(TrackpadEvent.DragEnd)
        runCurrent()

        assertEquals(
            ConnectionState.Reconnecting(HOST, 1, 1),
            repository.connectionState.first(),
        )

        repository.disconnect()
    }

    @Test
    fun `MOVE 전송 실패는 재연결을 트리거하지 않는다`() = runTest(dispatcher) {
        // F-2 유지: 고빈도 이벤트의 조용한 실패는 그대로 둔다.
        val repository = repositoryWith()
        repository.connect(HOST, PORT)
        runCurrent()
        clearMocks(tcpClient, answers = false, recordedCalls = true)
        coEvery { udpClient.send(any()) } throws java.io.IOException("udp down")

        repository.sendEvent(TrackpadEvent.Move(1f, 1f))
        advanceTimeBy(600_000)
        runCurrent()

        assertEquals(ConnectionState.Connected(HOST), repository.connectionState.first())
        coVerify(exactly = 0) { tcpClient.connect(any(), any()) }

        repository.disconnect()
    }

    @Test
    fun `SCROLL 전송 실패는 재연결을 트리거하지 않는다`() = runTest(dispatcher) {
        // F-1 유지: 스크롤도 고빈도라 실패를 조용히 버린다.
        val repository = repositoryWith()
        repository.connect(HOST, PORT)
        runCurrent()
        clearMocks(tcpClient, answers = false, recordedCalls = true)
        coEvery { tcpClient.send(any()) } throws java.io.IOException("tcp down")

        repository.sendEvent(TrackpadEvent.Scroll(dx = 1, dy = -1))
        runCurrent()

        assertEquals(ConnectionState.Connected(HOST), repository.connectionState.first())
        coVerify(exactly = 0) { tcpClient.connect(any(), any()) }

        repository.disconnect()
    }

    // --- 비활성 정책 = 재연결 도입 이전 동작 ------------------------------------------------

    @Test
    fun `재연결 비활성 정책이면 유실이 기존처럼 Error로 간다`() = runTest(dispatcher) {
        val repository = repositoryWith(ReconnectPolicy.Disabled)
        connectThenLose(repository)

        assertEquals(
            ConnectionState.Error("Connection lost"),
            repository.connectionState.first(),
        )

        advanceTimeBy(600_000)
        runCurrent()
        coVerify(exactly = 1) { tcpClient.connect(HOST, PORT) }
    }

    @Test
    fun `재연결 비활성 정책이면 Click 전송 실패도 기존처럼 Error로 간다`() = runTest(dispatcher) {
        val repository = repositoryWith(ReconnectPolicy.Disabled)
        repository.connect(HOST, PORT)
        runCurrent()
        coEvery { tcpClient.send(any()) } throws java.io.IOException("tcp down")

        repository.sendEvent(TrackpadEvent.Click("left"))
        runCurrent()

        assertEquals(ConnectionState.Error("tcp down"), repository.connectionState.first())
    }
}
