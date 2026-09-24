package com.example.phone_pad_app.data.repository

import com.example.phone_pad_app.data.network.TcpClient
import com.example.phone_pad_app.data.network.UdpClient
import com.example.phone_pad_app.domain.model.AuthFailedException
import com.example.phone_pad_app.domain.model.ConnectionErrorKind
import com.example.phone_pad_app.domain.model.ConnectionState
import com.example.phone_pad_app.domain.model.ReconnectPolicy
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
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

private const val AUTH_HOST = "192.168.0.10"
private const val AUTH_PORT = GestureConfig.DEFAULT_PORT
private const val AUTH_PIN = "483920"
private const val AUTH_SESSION = "0123456789abcdef0123456789abcdef"

/**
 * PIN이 리포지토리를 통과하는 경로 (Phase 5).
 *
 * 고정하는 계약:
 * - 사용자가 입력한 PIN이 가공 없이 [TcpClient.connect]로 내려간다.
 * - `AUTH_FAIL`은 `AUTH_FAILED` 종류의 실패로 분류되고, **첫 연결 실패는 재시도하지 않는다는
 *   기존 규칙**을 그대로 따른다(틀린 PIN으로 55초를 매달리면 안 된다).
 * - 자동 재연결은 성공했던 PIN을 다시 쓰고, 그 PIN이 거부되면 재시도를 소진한 뒤 `Error`가 된다.
 *
 * **각 테스트는 `Error`/`Disconnected` 상태나 `disconnect()`로 끝낸다** (AGENTS.md 섹션 6의
 * `runTest` 함정: 살아있는 heartbeat/재연결 루프는 무한 가상 시간 + OOM을 부른다).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TrackpadRepositoryAuthTest {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var tcpClient: TcpClient
    private lateinit var udpClient: UdpClient

    @Before
    fun setUp() {
        tcpClient = mockk(relaxed = true)
        udpClient = mockk(relaxed = true)
        coEvery { tcpClient.readLine() } coAnswers { awaitCancellation() }
    }

    private fun repositoryWith(policy: ReconnectPolicy = ReconnectPolicy.Disabled) =
        TrackpadRepositoryImpl(tcpClient, udpClient, dispatcher, policy)

    @Test
    fun `입력한 PIN이 그대로 TcpClient로 내려간다`() = runTest(dispatcher) {
        val repository = repositoryWith()
        coEvery { tcpClient.connect(AUTH_HOST, AUTH_PORT, AUTH_PIN) } returns AUTH_SESSION

        repository.connect(AUTH_HOST, AUTH_PORT, AUTH_PIN)
        runCurrent()

        coVerify(exactly = 1) { tcpClient.connect(AUTH_HOST, AUTH_PORT, AUTH_PIN) }
        assertEquals(ConnectionState.Connected(AUTH_HOST), repository.connectionState.first())

        repository.disconnect()
    }

    @Test
    fun `AUTH_FAIL은 AUTH_FAILED 종류의 Error가 된다`() = runTest(dispatcher) {
        val repository = repositoryWith()
        coEvery { tcpClient.connect(AUTH_HOST, AUTH_PORT, any()) } throws
            AuthFailedException("invalid_pin")

        repository.connect(AUTH_HOST, AUTH_PORT, "000000")
        runCurrent()

        val state = repository.connectionState.first()
        assertTrue("실제 상태: $state", state is ConnectionState.Error)
        assertEquals(ConnectionErrorKind.AUTH_FAILED, (state as ConnectionState.Error).kind)
        // 진단 원문은 그대로 남는다(사용자 문구는 표시 계층이 만든다 — AGENTS.md 섹션 9)
        assertTrue(state.message.contains("Auth failed"))
    }

    @Test
    fun `AUTH_FAIL은 핸드셰이크 실패와 다른 종류로 분류된다`() = runTest(dispatcher) {
        val repository = repositoryWith()
        coEvery { tcpClient.connect(AUTH_HOST, AUTH_PORT, any()) } returns null

        repository.connect(AUTH_HOST, AUTH_PORT, AUTH_PIN)
        runCurrent()

        // 세션이 오지 않은 것(= 서버가 아닐 수 있음)은 여전히 HANDSHAKE_FAILED다.
        val state = repository.connectionState.first() as ConnectionState.Error
        assertEquals(ConnectionErrorKind.HANDSHAKE_FAILED, state.kind)
    }

    @Test
    fun `AUTH_FAIL 뒤에는 소켓을 정리하고 UDP를 열지 않는다`() = runTest(dispatcher) {
        val repository = repositoryWith()
        coEvery { tcpClient.connect(AUTH_HOST, AUTH_PORT, any()) } throws AuthFailedException()

        repository.connect(AUTH_HOST, AUTH_PORT, "000000")
        runCurrent()

        verify { tcpClient.disconnect() }
        coVerify(exactly = 0) { udpClient.connect(any(), any()) }
    }

    @Test
    fun `첫 연결의 AUTH_FAIL은 재연결을 시작하지 않는다`() = runTest(dispatcher) {
        // 기존 규칙(첫 connect 실패는 재시도 금지)이 PIN 실패에도 그대로 적용되어야 한다 —
        // 틀린 PIN으로 재시도하면 서버의 브루트포스 카운터를 채워 IP가 잠긴다.
        val repository = repositoryWith(ReconnectPolicy.Default)
        coEvery { tcpClient.connect(AUTH_HOST, AUTH_PORT, any()) } throws
            AuthFailedException("invalid_pin")

        repository.connect(AUTH_HOST, AUTH_PORT, "000000")
        advanceTimeBy(600_000)
        runCurrent()

        coVerify(exactly = 1) { tcpClient.connect(AUTH_HOST, AUTH_PORT, any()) }
        val state = repository.connectionState.first()
        assertEquals(ConnectionErrorKind.AUTH_FAILED, (state as ConnectionState.Error).kind)
    }

    @Test
    fun `자동 재연결은 성공했던 PIN을 다시 쓴다`() = runTest(dispatcher) {
        // 재연결은 세션을 물려받지 않고 처음부터 connect()를 다시 타므로 AUTH도 다시 필요하다.
        val repository = repositoryWith(ReconnectPolicy.Default)
        coEvery { tcpClient.connect(AUTH_HOST, AUTH_PORT, AUTH_PIN) } returns AUTH_SESSION
        coEvery { tcpClient.readLine() } coAnswers {
            delay(1_000)
            null // EOF = 연결 유실
        }

        repository.connect(AUTH_HOST, AUTH_PORT, AUTH_PIN)
        runCurrent()
        advanceTimeBy(1_000)
        runCurrent()
        assertTrue(repository.connectionState.first() is ConnectionState.Reconnecting)

        clearMocks(tcpClient, answers = false, recordedCalls = true)
        advanceTimeBy(GestureConfig.RECONNECT_BASE_DELAY_MS)
        runCurrent()

        coVerify(atLeast = 1) { tcpClient.connect(AUTH_HOST, AUTH_PORT, AUTH_PIN) }

        repository.disconnect()
    }

    @Test
    fun `재연결 1회차에서 PIN이 거부되면 남은 백오프를 쓰지 않고 즉시 Error가 된다`() =
        runTest(dispatcher) {
            // 서버가 재시작되며 PIN이 바뀐 시나리오. PIN 불일치는 기다려서 해결되는 실패가
            // 아니고, 8회(55초)를 계속 두드리면 서버의 브루트포스 잠금(60초/5회)을 스스로
            // 유발해 **올바른 PIN으로 수동 재연결해도** 최대 60초간 막힌다.
            val policy = ReconnectPolicy.Default
            val repository = repositoryWith(policy)
            var calls = 0
            coEvery { tcpClient.connect(AUTH_HOST, AUTH_PORT, AUTH_PIN) } coAnswers {
                calls += 1
                if (calls == 1) AUTH_SESSION else throw AuthFailedException("invalid_pin")
            }
            coEvery { tcpClient.readLine() } coAnswers {
                delay(1_000)
                null // EOF = 연결 유실 → 재연결 시작
            }

            repository.connect(AUTH_HOST, AUTH_PORT, AUTH_PIN)
            runCurrent()
            advanceTimeBy(1_000)
            runCurrent()
            assertTrue(repository.connectionState.first() is ConnectionState.Reconnecting)

            // 1회차 백오프 뒤 첫 재시도가 AUTH_FAIL로 끝난다
            advanceTimeBy(policy.delayBeforeAttempt(1))
            runCurrent()

            val state = repository.connectionState.first()
            assertTrue("실제 상태: $state", state is ConnectionState.Error)
            // 종류는 RECONNECT_FAILED로 덮지 않는다 — 조치가 "PIN 다시 입력"으로 특정된다.
            assertEquals(ConnectionErrorKind.AUTH_FAILED, (state as ConnectionState.Error).kind)
            assertTrue(state.message.contains("Auth failed"))

            // 남은 7회를 절대 쓰지 않는다 (최초 1 + 재시도 1 = 2회로 끝)
            advanceTimeBy(600_000)
            runCurrent()
            assertEquals(2, calls)
            assertEquals(state, repository.connectionState.first())
        }

    @Test
    fun `AUTH_FAILED가 아닌 재연결 실패는 기존처럼 다음 백오프로 넘어간다`() = runTest(dispatcher) {
        // 즉시 중단은 **PIN 불일치에만** 적용된다. 일시적 네트워크 실패는 기다리면 복구되므로
        // 기존 백오프 재시도를 그대로 유지해야 한다(이 분기를 넓히면 재연결 기능이 죽는다).
        val policy = ReconnectPolicy.Default
        val repository = repositoryWith(policy)
        var calls = 0
        coEvery { tcpClient.connect(AUTH_HOST, AUTH_PORT, AUTH_PIN) } coAnswers {
            calls += 1
            if (calls == 1) AUTH_SESSION else throw java.net.ConnectException("refused")
        }
        coEvery { tcpClient.readLine() } coAnswers {
            delay(1_000)
            null
        }

        repository.connect(AUTH_HOST, AUTH_PORT, AUTH_PIN)
        runCurrent()
        advanceTimeBy(1_000)
        runCurrent()

        advanceTimeBy(policy.delayBeforeAttempt(1))
        runCurrent()

        // 1회차 실패 후에도 Error가 아니라 2회차 대기 상태여야 한다
        assertEquals(
            ConnectionState.Reconnecting(AUTH_HOST, 2, policy.maxAttempts),
            repository.connectionState.first(),
        )

        repository.disconnect()
    }

    @Test
    fun `인증이 꺼진 서버를 향한 빈 PIN 연결도 그대로 성립한다`() = runTest(dispatcher) {
        // 와이어 형식은 서버 설정에 따라 갈라지지 않는다 — 리포지토리는 값을 판정하지 않는다.
        val repository = repositoryWith()
        coEvery { tcpClient.connect(AUTH_HOST, AUTH_PORT, "") } returns AUTH_SESSION

        repository.connect(AUTH_HOST, AUTH_PORT, "")
        runCurrent()

        assertEquals(ConnectionState.Connected(AUTH_HOST), repository.connectionState.first())

        repository.disconnect()
    }
}
