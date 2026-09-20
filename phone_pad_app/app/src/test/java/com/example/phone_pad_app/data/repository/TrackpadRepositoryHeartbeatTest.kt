package com.example.phone_pad_app.data.repository

import com.example.phone_pad_app.data.network.TcpClient
import com.example.phone_pad_app.data.network.UdpClient
import com.example.phone_pad_app.domain.model.ConnectionErrorKind
import com.example.phone_pad_app.domain.model.ConnectionState
import com.example.phone_pad_app.domain.model.ReconnectPolicy
import com.example.phone_pad_app.domain.model.TrackpadEvent
import com.example.phone_pad_app.presentation.util.GestureConfig
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.SocketTimeoutException

private const val HOST = "192.168.0.10"
private const val SESSION_A = "0123456789abcdef0123456789abcdef"
private const val SESSION_B = "ffffffffffffffffffffffffffffffff"
private const val HEARTBEAT_JSON = """{"type":"HEARTBEAT"}"""
private const val ACK_JSON = """{"type":"HEARTBEAT_ACK"}"""
private const val INTERVAL = GestureConfig.HEARTBEAT_INTERVAL_MS

/**
 * heartbeat sender / watchdog 루프 검증 (가상 시간).
 *
 * 소켓의 읽기 타임아웃은 `readLine()`이 [GestureConfig.HEARTBEAT_INTERVAL_MS]만큼
 * 매달려 있다가 [SocketTimeoutException]을 던지는 것으로 모사한다 —
 * 실제 `soTimeout` 동작과 동일한 모양이므로 "5초 × 3회 ≈ 15초" 타이밍을 그대로 검증할 수 있다.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TrackpadRepositoryHeartbeatTest {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var tcpClient: TcpClient
    private lateinit var udpClient: UdpClient
    private lateinit var repository: TrackpadRepositoryImpl

    @Before
    fun setUp() {
        tcpClient = mockk(relaxed = true)
        udpClient = mockk(relaxed = true)
        // 이 클래스는 "유실 → Error" 전이 자체를 검증한다. 자동 재연결(Phase 4)은 그 전이를
        // Reconnecting으로 바꾸므로, 정책을 꺼서 재연결 도입 이전의 의미를 그대로 보존한다
        // (재연결 동작은 TrackpadRepositoryReconnectTest가 별도로 검증).
        repository = TrackpadRepositoryImpl(tcpClient, udpClient, dispatcher, ReconnectPolicy.Disabled)
        coEvery { tcpClient.connect(HOST, GestureConfig.DEFAULT_PORT) } returns SESSION_A
        // 기본 스텁: 서버가 아무것도 보내지 않는 상태로 계속 매달려 있는 읽기
        coEvery { tcpClient.readLine() } coAnswers { awaitCancellation() }
    }

    private suspend fun connect() = repository.connect(HOST, GestureConfig.DEFAULT_PORT)

    /** 읽기 타임아웃을 모사: 한 주기 동안 매달렸다가 SocketTimeoutException */
    private fun stubReadTimeouts() {
        coEvery { tcpClient.readLine() } coAnswers {
            delay(INTERVAL)
            throw SocketTimeoutException("Read timed out")
        }
    }

    private suspend fun TestScope.state(): ConnectionState = repository.connectionState.first()

    @Test
    fun `HEARTBEAT는 5초 주기로 TCP로만 전송된다`() = runTest(dispatcher) {
        connect()
        runCurrent()

        advanceTimeBy(INTERVAL - 1)
        runCurrent()
        coVerify(exactly = 0) { tcpClient.send(HEARTBEAT_JSON) }

        advanceTimeBy(1)
        runCurrent()
        coVerify(exactly = 1) { tcpClient.send(HEARTBEAT_JSON) }

        advanceTimeBy(INTERVAL * 2)
        runCurrent()
        coVerify(exactly = 3) { tcpClient.send(HEARTBEAT_JSON) }

        // heartbeat는 TCP 채널 전용 — UDP로 새어나가서는 안 된다 (AGENTS.md 섹션 4)
        coVerify(exactly = 0) { udpClient.send(any()) }

        repository.disconnect()
    }

    @Test
    fun `아무 줄이나 수신하면 미응답 카운터가 0으로 리셋된다`() = runTest(dispatcher) {
        var call = 0
        coEvery { tcpClient.readLine() } coAnswers {
            when (++call) {
                // 타임아웃 2회 (아직 한계 미달)
                1, 2 -> {
                    delay(INTERVAL)
                    throw SocketTimeoutException("Read timed out")
                }
                // ACK 수신 → 카운터 리셋
                3 -> {
                    delay(1_000)
                    ACK_JSON
                }
                // 다시 타임아웃 2회 — 리셋이 됐다면 한계(3)에 도달하지 못한다
                4, 5 -> {
                    delay(INTERVAL)
                    throw SocketTimeoutException("Read timed out")
                }
                else -> awaitCancellation()
            }
        }

        connect()
        advanceTimeBy(INTERVAL * 10)
        runCurrent()

        // 누적 타임아웃은 4회지만 연속 3회가 아니므로 연결은 유지된다
        assertEquals(ConnectionState.Connected(HOST), state())
        verify(exactly = 0) { tcpClient.disconnect() }

        repository.disconnect()
    }

    @Test
    fun `연속 3회 타임아웃이면 Heartbeat timeout Error로 전환하고 정리한다`() = runTest(dispatcher) {
        stubReadTimeouts()
        connect()
        clearMocks(tcpClient, udpClient, answers = false, recordedCalls = true)

        // 2회 미응답까지는 연결 유지 (5s, 10s)
        advanceTimeBy(INTERVAL * GestureConfig.HEARTBEAT_MISS_LIMIT - 1)
        runCurrent()
        assertEquals(ConnectionState.Connected(HOST), state())

        // 3회째(15s) 도달 → Error + cleanUp
        advanceTimeBy(1)
        runCurrent()
        assertEquals(ConnectionState.Error("Heartbeat timeout", ConnectionErrorKind.HEARTBEAT_TIMEOUT), state())
        verify(exactly = 1) { tcpClient.disconnect() }
        verify(exactly = 1) { udpClient.close() }

        // 판정 후에는 두 루프 모두 멈춘다 (좀비 heartbeat 금지)
        clearMocks(tcpClient, answers = false, recordedCalls = true)
        advanceTimeBy(INTERVAL * 5)
        runCurrent()
        coVerify(exactly = 0) { tcpClient.send(any()) }
    }

    @Test
    fun `EOF를 만나면 카운터를 기다리지 않고 즉시 Connection lost로 전환한다`() = runTest(dispatcher) {
        coEvery { tcpClient.readLine() } coAnswers {
            delay(2_000)
            null
        }
        connect()
        clearMocks(tcpClient, udpClient, answers = false, recordedCalls = true)

        advanceTimeBy(2_001)
        runCurrent()

        assertEquals(ConnectionState.Error("Connection lost", ConnectionErrorKind.CONNECTION_LOST), state())
        verify(exactly = 1) { tcpClient.disconnect() }
        verify(exactly = 1) { udpClient.close() }
    }

    @Test
    fun `IOException 등 기타 예외도 즉시 Connection lost로 전환한다`() = runTest(dispatcher) {
        coEvery { tcpClient.readLine() } coAnswers {
            delay(500)
            throw java.io.IOException("socket closed")
        }
        connect()

        advanceTimeBy(501)
        runCurrent()

        assertEquals(ConnectionState.Error("Connection lost", ConnectionErrorKind.CONNECTION_LOST), state())
    }

    @Test
    fun `HEARTBEAT 전송 실패는 즉시 Connection lost로 전환한다`() = runTest(dispatcher) {
        connect()
        coEvery { tcpClient.send(HEARTBEAT_JSON) } throws java.io.IOException("broken pipe")

        advanceTimeBy(INTERVAL)
        runCurrent()

        assertEquals(ConnectionState.Error("Connection lost", ConnectionErrorKind.CONNECTION_LOST), state())
        verify(exactly = 1) { tcpClient.disconnect() }
    }

    @Test
    fun `재연결하면 이전 루프가 취소되고 heartbeat는 한 번만 나간다`() = runTest(dispatcher) {
        connect()
        advanceTimeBy(INTERVAL)
        runCurrent()
        coVerify(exactly = 1) { tcpClient.send(HEARTBEAT_JSON) }

        coEvery { tcpClient.connect(HOST, GestureConfig.DEFAULT_PORT) } returns SESSION_B
        connect()
        clearMocks(tcpClient, answers = false, recordedCalls = true)

        advanceTimeBy(INTERVAL)
        runCurrent()

        // 옛 sender가 살아있다면 2회가 된다
        coVerify(exactly = 1) { tcpClient.send(HEARTBEAT_JSON) }
        assertEquals(ConnectionState.Connected(HOST), state())

        repository.disconnect()
    }

    @Test
    fun `disconnect 후에는 heartbeat가 멈추고 옛 루프가 상태를 덮어쓰지 못한다`() = runTest(dispatcher) {
        stubReadTimeouts()
        connect()

        // 타임아웃 2회까지 진행한 뒤(한계 도달 직전) 사용자가 직접 연결을 끊는다
        advanceTimeBy(INTERVAL * 2)
        runCurrent()
        repository.disconnect()
        clearMocks(tcpClient, answers = false, recordedCalls = true)

        advanceTimeBy(INTERVAL * 10)
        runCurrent()

        coVerify(exactly = 0) { tcpClient.send(any()) }
        // 취소된 watchdog이 뒤늦게 Error를 밀어넣지 않는다
        assertEquals(ConnectionState.Disconnected, state())
    }

    @Test
    fun `disconnect와 재연결은 이전 reader 코루틴을 실제로 취소한다`() = runTest(dispatcher) {
        // 소켓 읽기에 매달린 옛 reader가 좀비로 남으면 이 플래그가 false로 남는다.
        var readerCancellations = 0
        coEvery { tcpClient.readLine() } coAnswers {
            try {
                awaitCancellation()
            } finally {
                readerCancellations += 1
            }
        }

        connect()
        runCurrent()
        assertEquals(0, readerCancellations)

        // 재연결: 옛 루프가 먼저 취소되어야 한다
        coEvery { tcpClient.connect(HOST, GestureConfig.DEFAULT_PORT) } returns SESSION_B
        connect()
        runCurrent()
        assertEquals(1, readerCancellations)

        // disconnect: 새 루프도 취소된다
        repository.disconnect()
        runCurrent()
        assertEquals(2, readerCancellations)
    }

    @Test
    fun `핸드셰이크가 실패하면 heartbeat 루프를 시작하지 않는다`() = runTest(dispatcher) {
        coEvery { tcpClient.connect(HOST, GestureConfig.DEFAULT_PORT) } returns null

        connect()
        advanceTimeBy(INTERVAL * 5)
        runCurrent()

        coVerify(exactly = 0) { tcpClient.send(any()) }
        coVerify(exactly = 0) { tcpClient.readLine() }
        assertTrue(state() is ConnectionState.Error)
    }

    @Test
    fun `겹친 connect 호출은 직렬화되어 마지막 호출만 연결을 소유한다 (F-3)`() = runTest(dispatcher) {
        // 빠른 이중 탭처럼 connect()가 겹치면, Mutex가 없었을 때는 먼저 시작한 호출이
        // 나중에 완료되면서 살아있는 새 연결의 소켓을 닫거나(Connected인데 heartbeat가
        // 없는 상태) 상태를 잘못 덮어쓸 수 있었다(F-3). connectionMutex로 완전히
        // 직렬화되면 두 번째 호출이 첫 번째가 끝날 때까지 기다리므로 이런 교차가 없다.
        coEvery { tcpClient.connect(HOST, GestureConfig.DEFAULT_PORT) } coAnswers {
            delay(100)
            SESSION_A
        }

        val first = launch { connect() }
        runCurrent() // 첫 connect()가 핸드셰이크(딜레이) 중에 진입해 Mutex를 잡은 상태로 만든다

        // 첫 호출이 아직 안 끝났을 때 두 번째 connect() 요청이 들어온다
        coEvery { tcpClient.connect(HOST, GestureConfig.DEFAULT_PORT) } coAnswers {
            delay(100)
            SESSION_B
        }
        val second = launch { connect() }
        runCurrent()

        // sender/watchdog 루프가 무한히 도는 상태라 advanceUntilIdle()은 절대 idle이
        // 되지 않는다 — 두 handshake(각 100ms)가 끝나기에 충분한 시간만 진행한다.
        advanceTimeBy(200)
        runCurrent()
        first.join()
        second.join()

        // 직렬화됐다면 두 호출 다 성공하고, 최종 상태는 나중에 실행된 두 번째 세션을 반영한다
        coVerify(exactly = 2) { tcpClient.connect(HOST, GestureConfig.DEFAULT_PORT) }
        assertEquals(ConnectionState.Connected(HOST), state())

        // heartbeat 루프가 정확히 한 쌍만 살아있어야 한다 (옛 루프가 겹쳐 돌면 안 됨)
        clearMocks(tcpClient, udpClient, answers = false, recordedCalls = true)
        advanceTimeBy(INTERVAL)
        runCurrent()
        coVerify(exactly = 1) { tcpClient.send(HEARTBEAT_JSON) }

        // 살아있는 연결이 실제로 두 번째(SESSION_B) 세션을 쓰는지 MOVE로 확인
        repository.sendEvent(TrackpadEvent.Move(1f, 1f))
        val udpJson = io.mockk.slot<String>()
        coVerify { udpClient.send(capture(udpJson)) }
        assertTrue(udpJson.captured.contains(SESSION_B))

        repository.disconnect()
    }

    @Test
    fun `HEARTBEAT JSON은 확정 스펙 문자열과 정확히 일치한다`() = runTest(dispatcher) {
        connect()
        advanceTimeBy(INTERVAL)
        runCurrent()

        val json = io.mockk.slot<String>()
        coVerify { tcpClient.send(capture(json)) }
        assertEquals("""{"type":"HEARTBEAT"}""", json.captured)

        repository.disconnect()
    }
}
