package com.example.phone_pad_app.data.repository

import com.example.phone_pad_app.data.network.TcpClient
import com.example.phone_pad_app.data.network.UdpClient
import com.example.phone_pad_app.domain.model.ConnectionState
import com.example.phone_pad_app.domain.model.ReconnectPolicy
import com.example.phone_pad_app.presentation.util.GestureConfig
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
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

private const val HOST = "192.168.0.10"
private const val PORT = GestureConfig.DEFAULT_PORT
private const val SESSION = "0123456789abcdef0123456789abcdef"

/** 접속이 이 시간만큼 "걸리는" 것으로 모사한다 (가상 시간 — 실제 대기 없음). */
private const val CONNECT_DURATION_MS = 4_000L

/**
 * 첫 연결 취소 (`Connecting` 상태의 "취소" 버튼).
 *
 * 핵심 요구사항은 두 가지다:
 * 1. 진행 중인 시도를 끊고 **오류 없이** IP 입력 화면(`Disconnected`)으로 돌아간다.
 * 2. **취소된 시도의 뒤늦은 결과가 상태를 덮어쓰지 않는다.** 블로킹 `connect()`는 코루틴
 *    취소로 풀리지 않아 취소 뒤에도 살아 있다가 성공/실패하므로, 그 결과가 `Connected`나
 *    `Error`를 쓰면 사용자가 방금 누른 취소가 없던 일이 된다.
 *
 * 접속 소요 시간은 가상 시간으로 모사한다 — 실제 소켓도, 실제 5초 대기도 없다.
 *
 * **각 테스트는 `Disconnected`나 `repository.disconnect()`로 끝낸다** (AGENTS.md 섹션 6
 * "테스트 함정": heartbeat/재연결 루프를 살려 둔 채 `runTest`를 끝내면 무한 루프 + OOM).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TrackpadRepositoryCancelConnectTest {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var tcpClient: TcpClient
    private lateinit var udpClient: UdpClient
    private lateinit var repository: TrackpadRepositoryImpl

    @Before
    fun setUp() {
        tcpClient = mockk(relaxed = true)
        udpClient = mockk(relaxed = true)
        // 연결 유지 중에는 아무것도 오지 않는 읽기(= watchdog이 조용히 매달려 있음)
        coEvery { tcpClient.readLine() } coAnswers { awaitCancellation() }
        repository = TrackpadRepositoryImpl(
            tcpClient,
            udpClient,
            dispatcher,
            ReconnectPolicy.Default,
        )
    }

    /** 접속이 [CONNECT_DURATION_MS] 동안 매달렸다가 [result]를 돌려주도록 만든다. */
    private fun stubSlowConnect(result: String?) {
        coEvery { tcpClient.connect(HOST, PORT) } coAnswers {
            delay(CONNECT_DURATION_MS)
            result
        }
    }

    /** 접속이 [CONNECT_DURATION_MS] 동안 매달렸다가 실패하도록 만든다. */
    private fun stubSlowConnectFailure(error: Throwable) {
        coEvery { tcpClient.connect(HOST, PORT) } coAnswers {
            delay(CONNECT_DURATION_MS)
            throw error
        }
    }

    private suspend fun state() = repository.connectionState.first()

    @Test
    fun `취소하면 오류 없이 Disconnected로 돌아간다`() = runTest(dispatcher) {
        stubSlowConnect(SESSION)
        val attempt = launch { repository.connect(HOST, PORT) }
        runCurrent()
        assertEquals(ConnectionState.Connecting, state())

        repository.cancelConnect()

        // Error가 아니라 Disconnected다 — 사용자가 스스로 그만둔 것을 오류로 보고하면 안 된다.
        assertEquals(ConnectionState.Disconnected, state())
        attempt.cancel()
    }

    @Test
    fun `취소는 소켓을 닫아 블로킹 접속을 깨운다`() = runTest(dispatcher) {
        stubSlowConnect(SESSION)
        val attempt = launch { repository.connect(HOST, PORT) }
        runCurrent()

        repository.cancelConnect()

        // 코루틴 취소만으로는 블로킹 connect()가 풀리지 않는다. 소켓을 닫는 것이 유일한 수단.
        verify(atLeast = 1) { tcpClient.disconnect() }
        attempt.cancel()
    }

    @Test
    fun `취소된 시도가 뒤늦게 성공해도 Connected로 덮어쓰지 않는다`() = runTest(dispatcher) {
        stubSlowConnect(SESSION)
        launch { repository.connect(HOST, PORT) }
        runCurrent()
        repository.cancelConnect()

        // 취소 직후 서버가 응답한 경우 — 시도는 이미 무효다.
        advanceTimeBy(CONNECT_DURATION_MS + 1)
        runCurrent()

        assertEquals(ConnectionState.Disconnected, state())
        // 붙어버린 소켓을 방치하면 서버에 유령 세션이 남는다 — 정리까지 확인한다.
        verify(atLeast = 2) { tcpClient.disconnect() }
        // 취소된 연결로 UDP 타깃을 열어서도 안 된다.
        coVerify(exactly = 0) { udpClient.connect(any(), any()) }
    }

    @Test
    fun `취소된 시도가 뒤늦게 실패해도 Error로 덮어쓰지 않는다`() = runTest(dispatcher) {
        stubSlowConnectFailure(java.net.ConnectException("refused"))
        launch { repository.connect(HOST, PORT) }
        runCurrent()
        repository.cancelConnect()

        advanceTimeBy(CONNECT_DURATION_MS + 1)
        runCurrent()

        // 취소한 화면에 뒤늦게 빨간 오류가 뜨면 안 된다.
        assertEquals(ConnectionState.Disconnected, state())
    }

    @Test
    fun `취소한 뒤 다시 연결하면 정상적으로 붙는다`() = runTest(dispatcher) {
        stubSlowConnect(SESSION)
        launch { repository.connect(HOST, PORT) }
        runCurrent()
        repository.cancelConnect()
        advanceTimeBy(CONNECT_DURATION_MS + 1)
        runCurrent()

        // 취소가 어떤 플래그를 영구히 망가뜨리지 않았는지 — 이번에는 즉시 성공하게 둔다.
        coEvery { tcpClient.connect(HOST, PORT) } returns SESSION
        repository.connect(HOST, PORT)
        runCurrent()

        assertEquals(ConnectionState.Connected(HOST), state())
        repository.disconnect()
    }

    @Test
    fun `이미 연결된 상태에서의 취소는 연결을 끊지 않는다`() = runTest(dispatcher) {
        coEvery { tcpClient.connect(HOST, PORT) } returns SESSION
        repository.connect(HOST, PORT)
        runCurrent()
        assertEquals(ConnectionState.Connected(HOST), state())

        // 접속에 성공한 바로 그 순간 눌린 취소가 살아있는 연결을 끊어버리면 안 된다.
        repository.cancelConnect()
        runCurrent()

        assertEquals(ConnectionState.Connected(HOST), state())
        repository.disconnect()
    }

    @Test
    fun `재연결 중의 취소는 재연결을 건드리지 않는다`() = runTest(dispatcher) {
        // 재연결 취소는 disconnect()가 담당한다 — cancelConnect()는 첫 연결 전용이다.
        coEvery { tcpClient.connect(HOST, PORT) } returns SESSION
        coEvery { tcpClient.readLine() } coAnswers {
            delay(1_000L)
            null // EOF = 연결 유실
        }
        repository.connect(HOST, PORT)
        runCurrent()
        advanceTimeBy(1_000L)
        runCurrent()
        assertTrue(state() is ConnectionState.Reconnecting)

        repository.cancelConnect()
        runCurrent()

        assertTrue(state() is ConnectionState.Reconnecting)
        repository.disconnect()
    }

    @Test
    fun `연결하지 않은 상태에서의 취소는 아무 일도 하지 않는다`() = runTest(dispatcher) {
        repository.cancelConnect()
        runCurrent()

        assertEquals(ConnectionState.Disconnected, state())
        verify(exactly = 0) { tcpClient.disconnect() }
    }
}
