package com.example.phone_pad_app.data.repository

import com.example.phone_pad_app.data.network.TcpClient
import com.example.phone_pad_app.data.network.UdpClient
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
private const val PIN = "483920"
private const val PORT = GestureConfig.DEFAULT_PORT
private const val SESSION_A = "0123456789abcdef0123456789abcdef"
private const val SESSION_B = "ffffffffffffffffffffffffffffffff"
private const val INTERVAL = GestureConfig.HEARTBEAT_INTERVAL_MS
private const val NOTICE_AT_MS = 1_000L

/** 확정 와이어 스펙 (AGENTS.md 섹션 4). 필드 없음, session 없음. */
private const val SESSION_REPLACED_JSON = """{"type":"SESSION_REPLACED"}"""
private const val ACK_JSON = """{"type":"HEARTBEAT_ACK"}"""

/** [TrackpadRepositoryImpl]의 내부 진단 문자열 계약 (사용자에게 보이는 문구가 아니다). */
private const val MESSAGE_SESSION_REPLACED = "Session replaced by another device"

/** 이 종류로 끝난 연결에 기대하는 최종 상태. */
private val REPLACED_ERROR =
    ConnectionState.Error(MESSAGE_SESSION_REPLACED, ConnectionErrorKind.SESSION_REPLACED)

/**
 * 단일 클라이언트 정책(Phase 5) — 밀려난 기기의 동작 검증. 전부 가상 시간이다.
 *
 * 고정하는 계약은 하나다: **`SESSION_REPLACED`를 받으면 자동 재연결을 시작하지 않는다.**
 * 그래서 대부분의 테스트가 [ReconnectPolicy.Default](= 실제 앱 정책)를 주입한 채로 검증한다.
 * 정책을 꺼 놓고 확인하면 재연결이 안 도는 게 당연해져 테스트가 아무것도 증명하지 못한다.
 *
 * **모든 테스트가 [withRepository]를 거치는 이유**(AGENTS.md 섹션 10 `runTest` 함정의 확장판):
 * 리포지토리의 keep-alive/재연결 스코프는 테스트 스코프의 자식이 아니라서, 본문이 예외로
 * 끝나도 자동으로 취소되지 않는다. 연결이 살아 있는 채로 단언이 깨지면 `runTest`의 정리
 * 단계가 영원히 도는 가상 시간을 따라가느라 **테스트가 실패하는 대신 멈춘다**(이 클래스를
 * 작성하며 실제로 관측했다 — 판정 분기를 일부러 무력화했더니 10분을 넘겨도 끝나지 않았다).
 * `finally`에서 반드시 `disconnect()`를 불러 회귀가 깔끔한 실패로 드러나게 한다.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TrackpadRepositorySessionReplacedTest {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var tcpClient: TcpClient
    private lateinit var udpClient: UdpClient

    @Before
    fun setUp() {
        tcpClient = mockk(relaxed = true)
        udpClient = mockk(relaxed = true)
        coEvery { tcpClient.connect(HOST, PORT, PIN) } returns SESSION_A
        // 기본: 서버가 아무것도 보내지 않는 상태로 매달려 있는 읽기(= 연결 유지)
        coEvery { tcpClient.readLine() } coAnswers { awaitCancellation() }
    }

    /** 리포지토리 하나를 만들어 [block]에 넘기고, 성공/실패와 무관하게 반드시 정리한다. */
    private suspend fun TestScope.withRepository(
        policy: ReconnectPolicy = ReconnectPolicy.Default,
        block: suspend (TrackpadRepositoryImpl) -> Unit,
    ) {
        val repository = TrackpadRepositoryImpl(tcpClient, udpClient, dispatcher, policy)
        try {
            block(repository)
        } finally {
            repository.disconnect()
            runCurrent()
        }
    }

    private suspend fun TrackpadRepositoryImpl.state(): ConnectionState = connectionState.first()

    /**
     * [NOTICE_AT_MS] 뒤에 알림 한 줄을 보내고, 그 뒤로는 서버가 소켓을 닫은 것처럼 EOF를 준다.
     *
     * 뒤의 EOF가 중요하다 — 앱이 알림을 무시하면 곧바로 "그냥 끊김"으로 관측되어 자동 재연결이
     * 시작되므로, 오동작이 눈에 보이게 된다.
     */
    private fun stubSessionReplaced() {
        var sent = false
        coEvery { tcpClient.readLine() } coAnswers {
            delay(NOTICE_AT_MS)
            if (!sent) {
                sent = true
                SESSION_REPLACED_JSON
            } else {
                null
            }
        }
    }

    /** 연결 → [NOTICE_AT_MS] 경과(= 알림 수신)까지 진행시킨다. */
    private suspend fun TestScope.connectThenReplaced(repository: TrackpadRepositoryImpl) {
        stubSessionReplaced()
        repository.connect(HOST, PORT, PIN)
        runCurrent()
        advanceTimeBy(NOTICE_AT_MS)
        runCurrent()
    }

    // --- 핵심 계약 --------------------------------------------------------------------

    @Test
    fun `SESSION_REPLACED를 받으면 재연결하지 않고 곧바로 Error로 간다`() = runTest(dispatcher) {
        withRepository { repository ->
            val seen = mutableListOf<ConnectionState>()
            val collector = launch { repository.connectionState.collect { seen += it } }

            stubSessionReplaced()
            repository.connect(HOST, PORT, PIN)
            runCurrent()
            assertEquals(ConnectionState.Connected(HOST), repository.state())

            advanceTimeBy(NOTICE_AT_MS)
            runCurrent()

            assertEquals(REPLACED_ERROR, repository.state())
            // 재연결 정책이 켜져 있어도 Reconnecting을 한 프레임도 거치지 않는다.
            assertTrue("Reconnecting을 거쳤다: $seen", seen.none { it is ConnectionState.Reconnecting })

            collector.cancel()
        }
    }

    @Test
    fun `밀려난 뒤에는 재접속 시도가 전혀 일어나지 않는다`() = runTest(dispatcher) {
        withRepository { repository ->
            connectThenReplaced(repository)
            clearMocks(tcpClient, udpClient, answers = false, recordedCalls = true)

            // 기본 정책의 백오프 전체(1+2+4+8+10s... 8회 ~= 55초)를 넉넉히 넘겨도 조용해야 한다.
            advanceTimeBy(120_000)
            runCurrent()

            coVerify(exactly = 0) { tcpClient.connect(any(), any(), any()) }
            // 좀비 heartbeat sender도 남지 않는다.
            coVerify(exactly = 0) { tcpClient.send(any()) }
            assertEquals(REPLACED_ERROR, repository.state())
        }
    }

    @Test
    fun `밀려난 소켓은 정리된다`() = runTest(dispatcher) {
        withRepository { repository ->
            stubSessionReplaced()
            repository.connect(HOST, PORT, PIN)
            runCurrent()
            clearMocks(tcpClient, udpClient, answers = false, recordedCalls = true)

            advanceTimeBy(NOTICE_AT_MS)
            runCurrent()

            verify(atLeast = 1) { tcpClient.disconnect() }
            verify(atLeast = 1) { udpClient.close() }
        }
    }

    @Test
    fun `재연결이 꺼진 정책에서도 종류는 CONNECTION_LOST가 아니라 SESSION_REPLACED다`() =
        runTest(dispatcher) {
            withRepository(ReconnectPolicy.Disabled) { repository ->
                connectThenReplaced(repository)

                assertEquals(REPLACED_ERROR, repository.state())
            }
        }

    @Test
    fun `알림은 뒤따르는 EOF를 기다리지 않고 즉시 판정한다`() = runTest(dispatcher) {
        // 알림 줄과 EOF 사이에 시간이 있어도(서버 shutdown이 한 틱 늦어도) 그 사이에 이미
        // Error여야 한다 — EOF를 기다리면 "그냥 끊김"으로 분류되어 재연결이 시작된다.
        withRepository { repository ->
            var sent = false
            coEvery { tcpClient.readLine() } coAnswers {
                if (!sent) {
                    sent = true
                    delay(NOTICE_AT_MS)
                    SESSION_REPLACED_JSON
                } else {
                    delay(60_000)
                    null
                }
            }

            repository.connect(HOST, PORT, PIN)
            runCurrent()
            advanceTimeBy(NOTICE_AT_MS)
            runCurrent()

            assertEquals(REPLACED_ERROR, repository.state())
            // watchdog이 곧바로 return 했으므로 두 번째 읽기 자체가 없다.
            coVerify(exactly = 1) { tcpClient.readLine() }
        }
    }

    @Test
    fun `밀려난 뒤 사용자가 직접 다시 연결하면 정상 동작한다`() = runTest(dispatcher) {
        // 막는 것은 **자동** 재연결뿐이다. 수동 재연결까지 막으면 사용자가 기기를 되찾을 수 없다.
        withRepository { repository ->
            connectThenReplaced(repository)
            assertEquals(REPLACED_ERROR, repository.state())

            coEvery { tcpClient.connect(HOST, PORT, PIN) } returns SESSION_B
            coEvery { tcpClient.readLine() } coAnswers { awaitCancellation() }
            repository.connect(HOST, PORT, PIN)
            runCurrent()

            assertEquals(ConnectionState.Connected(HOST), repository.state())
        }
    }

    // --- 회귀: 다른 줄은 지금까지와 똑같이 취급한다 ----------------------------------------

    @Test
    fun `HEARTBEAT_ACK는 기존처럼 카운터만 리셋한다`() = runTest(dispatcher) {
        withRepository { repository ->
            stubLineBetweenTimeouts(ACK_JSON)

            repository.connect(HOST, PORT, PIN)
            advanceTimeBy(INTERVAL * 10)
            runCurrent()

            // 누적 타임아웃 4회지만 연속 3회가 아니므로 연결 유지 — SESSION_REPLACED 분기를
            // 끼워 넣으면서 이 경로가 망가지지 않았는지 고정한다.
            assertEquals(ConnectionState.Connected(HOST), repository.state())
        }
    }

    @Test
    fun `알 수 없는 하향 줄도 기존처럼 카운터만 리셋한다`() = runTest(dispatcher) {
        withRepository { repository ->
            // 파싱하지 않는 임의의 줄. "아무 줄이나 수신 = 살아 있음" 계약이 그대로여야 한다.
            stubLineBetweenTimeouts("""{"type":"SOMETHING_NEW","note":"SESSION"}""")

            repository.connect(HOST, PORT, PIN)
            advanceTimeBy(INTERVAL * 10)
            runCurrent()

            assertEquals(ConnectionState.Connected(HOST), repository.state())
        }
    }

    @Test
    fun `평범한 EOF는 여전히 재연결을 시작한다`() = runTest(dispatcher) {
        // SESSION_REPLACED 예외가 일반 유실 경로까지 삼키지 않았는지 고정한다.
        withRepository { repository ->
            coEvery { tcpClient.readLine() } coAnswers {
                delay(NOTICE_AT_MS)
                null
            }

            repository.connect(HOST, PORT, PIN)
            runCurrent()
            advanceTimeBy(NOTICE_AT_MS)
            runCurrent()

            assertEquals(
                ConnectionState.Reconnecting(HOST, 1, GestureConfig.RECONNECT_MAX_ATTEMPTS),
                repository.state(),
            )
        }
    }

    /** 타임아웃 2회 -> [line] 1회 -> 타임아웃 2회. 리셋이 동작하면 한계(3회)에 닿지 않는다. */
    private fun stubLineBetweenTimeouts(line: String) {
        var call = 0
        coEvery { tcpClient.readLine() } coAnswers {
            when (++call) {
                1, 2, 4, 5 -> {
                    delay(INTERVAL)
                    throw SocketTimeoutException("Read timed out")
                }
                3 -> {
                    delay(1_000)
                    line
                }
                else -> awaitCancellation()
            }
        }
    }
}
