package com.example.phone_pad_app.data.repository

import com.example.phone_pad_app.data.network.TcpClient
import com.example.phone_pad_app.data.network.UdpClient
import com.example.phone_pad_app.domain.model.ConnectionErrorKind
import com.example.phone_pad_app.domain.model.ConnectionState
import com.example.phone_pad_app.domain.model.ReconnectPolicy
import com.example.phone_pad_app.domain.model.TrackpadEvent
import com.example.phone_pad_app.presentation.trackpad.MultiTouchGestureTracker
import com.example.phone_pad_app.presentation.util.GestureConfig
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import io.mockk.coVerifyOrder
import io.mockk.verify
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

private const val HOST = "192.168.0.10"

/**
 * connect()에 넘기는 PIN. 이 테스트들은 **인증이 꺼진 서버**를 모사하므로 값 자체는 무관하고,
 * 고정하는 것은 "PIN이 TcpClient까지 그대로 전달된다"는 계약뿐이다
 * (와이어 형식은 TcpClientAuthTest가 고정한다).
 */
private const val PIN = "483920"
private const val SESSION = "0123456789abcdef0123456789abcdef"

class TrackpadRepositoryImplTest {

    private lateinit var tcpClient: TcpClient
    private lateinit var udpClient: UdpClient
    private lateinit var repository: TrackpadRepositoryImpl

    /**
     * heartbeat 루프는 이 디스패처에 올라간다.
     * 이 테스트 클래스는 스케줄러를 진행시키지 않으므로 루프 본문은 실행되지 않으며,
     * 채널 분기/세션 관련 검증에 영향을 주지 않는다 (타이밍 검증은 TrackpadRepositoryHeartbeatTest).
     */
    private val loopDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        tcpClient = mockk(relaxed = true)
        udpClient = mockk(relaxed = true)
        // 전송 실패 → Error 전이를 검증하는 테스트들이 있으므로 자동 재연결은 꺼 둔다
        // (재연결이 켜지면 같은 실패가 Reconnecting으로 간다 — 그 동작은
        // TrackpadRepositoryReconnectTest가 검증한다).
        repository = TrackpadRepositoryImpl(
            tcpClient,
            udpClient,
            loopDispatcher,
            ReconnectPolicy.Disabled,
        )
    }

    private suspend fun connectSuccessfully() {
        coEvery { tcpClient.connect(HOST, GestureConfig.DEFAULT_PORT, PIN) } returns SESSION
        repository.connect(HOST, GestureConfig.DEFAULT_PORT, PIN)
    }

    @Test
    fun `connect는 TCP 핸드셰이크 후 UDP 채널을 9001로 준비하고 Connected로 전환한다`() = runTest {
        connectSuccessfully()

        coVerify(exactly = 1) { tcpClient.connect(HOST, GestureConfig.DEFAULT_PORT, PIN) }
        coVerify(exactly = 1) { udpClient.connect(HOST, 9001) }
        assertEquals(9001, GestureConfig.UDP_PORT)
        assertEquals(ConnectionState.Connected(HOST), repository.connectionState.first())
    }

    @Test
    fun `Move 이벤트는 session 필드를 포함해 UDP로만 전송된다`() = runTest {
        connectSuccessfully()

        repository.sendEvent(TrackpadEvent.Move(dx = 2.5f, dy = -1.0f))

        val json = slot<String>()
        coVerify(exactly = 1) { udpClient.send(capture(json)) }
        assertEquals(
            """{"session":"$SESSION","type":"MOVE","dx":2.5,"dy":-1.0}""",
            json.captured
        )
        // MOVE는 절대 TCP로 새어나가지 않는다 (AGENTS.md 섹션 4 원칙)
        coVerify(exactly = 0) { tcpClient.send(any()) }
    }

    @Test
    fun `Move JSON에는 개행이 붙지 않는다`() = runTest {
        connectSuccessfully()

        repository.sendEvent(TrackpadEvent.Move(1f, 1f))

        val json = slot<String>()
        coVerify { udpClient.send(capture(json)) }
        assertFalse(json.captured.contains("\n"))
    }

    @Test
    fun `Click 이벤트는 기존처럼 TCP로 전송되고 session 필드를 포함하지 않는다`() = runTest {
        connectSuccessfully()

        repository.sendEvent(TrackpadEvent.Click("left"))

        val json = slot<String>()
        coVerify(exactly = 1) { tcpClient.send(capture(json)) }
        assertEquals("""{"type":"CLICK","button":"left"}""", json.captured)
        coVerify(exactly = 0) { udpClient.send(any()) }
    }

    @Test
    fun `DoubleClick 이벤트는 TCP로 전송되고 session 필드를 포함하지 않는다`() = runTest {
        connectSuccessfully()

        repository.sendEvent(TrackpadEvent.DoubleClick("left"))

        val json = slot<String>()
        coVerify(exactly = 1) { tcpClient.send(capture(json)) }
        // AGENTS.md 섹션 4의 와이어 포맷을 리터럴로 고정한다 (서버 handle_event와의 계약)
        assertEquals("""{"type":"DOUBLE_CLICK","button":"left"}""", json.captured)
        // 이동 좌표가 아니므로 UDP로 새면 안 된다
        coVerify(exactly = 0) { udpClient.send(any()) }
    }

    @Test
    fun `DoubleClick 기본 button은 left다`() = runTest {
        connectSuccessfully()

        repository.sendEvent(TrackpadEvent.DoubleClick())

        val json = slot<String>()
        coVerify(exactly = 1) { tcpClient.send(capture(json)) }
        assertEquals("""{"type":"DOUBLE_CLICK","button":"left"}""", json.captured)
    }

    @Test
    fun `DoubleClick은 CLICK 두 개로 쪼개지지 않는다`() = runTest {
        // 이번 스펙의 핵심: 개별 CLICK 두 개가 아니라 DOUBLE_CLICK 한 줄만 나가야 한다.
        connectSuccessfully()

        repository.sendEvent(TrackpadEvent.DoubleClick())

        coVerify(exactly = 1) { tcpClient.send(any()) }
        coVerify(exactly = 0) { tcpClient.send(match { it.contains(""""type":"CLICK"""") }) }
    }

    @Test
    fun `DoubleClick 전송 실패는 CLICK과 같이 Error로 알린다`() = runTest {
        // MOVE/SCROLL과 달리 저빈도 · 사용자 명시 행동이므로 조용히 버리지 않는다.
        connectSuccessfully()
        coEvery { tcpClient.send(any()) } throws java.io.IOException("tcp down")

        repository.sendEvent(TrackpadEvent.DoubleClick())

        assertEquals(ConnectionState.Error("tcp down", ConnectionErrorKind.CONNECTION_LOST), repository.connectionState.first())
    }

    @Test
    fun `Scroll 이벤트는 정수 스텝을 담아 TCP로 전송된다`() = runTest {
        connectSuccessfully()

        repository.sendEvent(TrackpadEvent.Scroll(dx = 0, dy = -3))

        val json = slot<String>()
        coVerify(exactly = 1) { tcpClient.send(capture(json)) }
        // AGENTS.md 섹션 4의 와이어 포맷을 리터럴로 고정한다 (서버 handle_event와의 계약)
        assertEquals("""{"type":"SCROLL","dx":0,"dy":-3}""", json.captured)
        // SCROLL은 이동 좌표가 아니므로 UDP로 새면 안 된다
        coVerify(exactly = 0) { udpClient.send(any()) }
    }

    @Test
    fun `Scroll JSON은 session 필드를 포함하지 않고 소수점도 붙지 않는다`() = runTest {
        connectSuccessfully()

        repository.sendEvent(TrackpadEvent.Scroll(dx = 2, dy = 5))

        val json = slot<String>()
        coVerify(exactly = 1) { tcpClient.send(capture(json)) }
        assertFalse("CLICK과 같은 TCP 평문 이벤트다", json.captured.contains("session"))
        assertFalse("dx/dy는 정수 스텝이다", json.captured.contains("."))
        assertEquals("""{"type":"SCROLL","dx":2,"dy":5}""", json.captured)
    }

    @Test
    fun `DragStart는 필드 없는 DRAG_START 한 줄로 TCP에 나간다`() = runTest {
        connectSuccessfully()

        repository.sendEvent(TrackpadEvent.DragStart)

        val json = slot<String>()
        coVerify(exactly = 1) { tcpClient.send(capture(json)) }
        // AGENTS.md 섹션 4의 와이어 포맷을 리터럴로 고정한다 (서버 handle_event와의 계약)
        assertEquals("""{"type":"DRAG_START"}""", json.captured)
        assertFalse("session 필드가 없는 평문 이벤트다", json.captured.contains("session"))
        // 이동 좌표가 아니므로 UDP로 새면 안 된다
        coVerify(exactly = 0) { udpClient.send(any()) }
    }

    @Test
    fun `DragEnd는 필드 없는 DRAG_END 한 줄로 TCP에 나간다`() = runTest {
        connectSuccessfully()

        repository.sendEvent(TrackpadEvent.DragEnd)

        val json = slot<String>()
        coVerify(exactly = 1) { tcpClient.send(capture(json)) }
        assertEquals("""{"type":"DRAG_END"}""", json.captured)
        assertFalse("session 필드가 없는 평문 이벤트다", json.captured.contains("session"))
        coVerify(exactly = 0) { udpClient.send(any()) }
    }

    @Test
    fun `드래그 시작과 종료는 서로 다른 줄로 구분된다`() = runTest {
        connectSuccessfully()

        repository.sendEvent(TrackpadEvent.DragStart)
        repository.sendEvent(TrackpadEvent.DragEnd)

        coVerifyOrder {
            tcpClient.send("""{"type":"DRAG_START"}""")
            tcpClient.send("""{"type":"DRAG_END"}""")
        }
    }

    @Test
    fun `DragStart 전송 실패는 CLICK과 같이 Error로 알린다`() = runTest {
        connectSuccessfully()
        coEvery { tcpClient.send(any()) } throws java.io.IOException("tcp down")

        repository.sendEvent(TrackpadEvent.DragStart)

        assertEquals(ConnectionState.Error("tcp down", ConnectionErrorKind.CONNECTION_LOST), repository.connectionState.first())
    }

    @Test
    fun `DragEnd 전송 실패는 Error로 알린다`() = runTest {
        // DRAG_END 유실은 PC 버튼이 눌린 채 남는 최악의 상태라 조용히 버리면 안 된다.
        connectSuccessfully()
        coEvery { tcpClient.send(any()) } throws java.io.IOException("tcp down")

        repository.sendEvent(TrackpadEvent.DragEnd)

        assertEquals(ConnectionState.Error("tcp down", ConnectionErrorKind.CONNECTION_LOST), repository.connectionState.first())
    }

    @Test
    fun `DesktopSwitch left는 확정된 와이어 리터럴 그대로 TCP에 나간다`() = runTest {
        connectSuccessfully()

        repository.sendEvent(
            TrackpadEvent.DesktopSwitch(MultiTouchGestureTracker.DIRECTION_LEFT)
        )

        val json = slot<String>()
        coVerify(exactly = 1) { tcpClient.send(capture(json)) }
        // AGENTS.md 섹션 4의 와이어 포맷을 리터럴로 고정한다 (서버 handle_event와의 계약).
        // 공백 없음, 필드 순서 type → direction.
        assertEquals("""{"type":"DESKTOP_SWITCH","direction":"left"}""", json.captured)
        assertFalse("session 필드가 없는 평문 이벤트다", json.captured.contains("session"))
        // 이동 좌표가 아니므로 UDP로 새면 안 된다
        coVerify(exactly = 0) { udpClient.send(any()) }
    }

    @Test
    fun `DesktopSwitch right도 같은 형식으로 나간다`() = runTest {
        connectSuccessfully()

        repository.sendEvent(
            TrackpadEvent.DesktopSwitch(MultiTouchGestureTracker.DIRECTION_RIGHT)
        )

        val json = slot<String>()
        coVerify(exactly = 1) { tcpClient.send(capture(json)) }
        assertEquals("""{"type":"DESKTOP_SWITCH","direction":"right"}""", json.captured)
    }

    @Test
    fun `DesktopSwitch는 direction 값을 뒤집지 않고 그대로 싣는다`() = runTest {
        // 손가락 방향 → 와이어 방향 매핑은 MultiTouchGestureTracker 한 곳에서만 한다.
        // data 계층이 한 번 더 뒤집으면 서버와 방향이 어긋난다.
        connectSuccessfully()

        repository.sendEvent(TrackpadEvent.DesktopSwitch("left"))
        repository.sendEvent(TrackpadEvent.DesktopSwitch("right"))

        coVerifyOrder {
            tcpClient.send("""{"type":"DESKTOP_SWITCH","direction":"left"}""")
            tcpClient.send("""{"type":"DESKTOP_SWITCH","direction":"right"}""")
        }
    }

    @Test
    fun `DesktopSwitch 전송 실패는 CLICK과 같이 Error로 알린다`() = runTest {
        // MOVE/SCROLL의 "조용한 실패"가 아니라 저빈도 · 사용자 명시 행동 경로다.
        connectSuccessfully()
        coEvery { tcpClient.send(any()) } throws java.io.IOException("tcp down")

        repository.sendEvent(TrackpadEvent.DesktopSwitch("left"))

        assertEquals(
            ConnectionState.Error("tcp down", ConnectionErrorKind.CONNECTION_LOST),
            repository.connectionState.first(),
        )
    }

    @Test
    fun `핸드셰이크가 오지 않으면 Error로 전환하고 UDP를 준비하지 않는다`() = runTest {
        coEvery { tcpClient.connect(HOST, GestureConfig.DEFAULT_PORT, PIN) } returns null

        repository.connect(HOST, GestureConfig.DEFAULT_PORT, PIN)

        coVerify(exactly = 0) { udpClient.connect(any(), any()) }
        verify { tcpClient.disconnect() }
        assertTrue(repository.connectionState.first() is ConnectionState.Error)
    }

    @Test
    fun `TCP 연결이 실패하면 Error로 전환하고 소켓을 정리한다`() = runTest {
        coEvery { tcpClient.connect(HOST, GestureConfig.DEFAULT_PORT, PIN) } throws
            java.net.ConnectException("refused")

        repository.connect(HOST, GestureConfig.DEFAULT_PORT, PIN)

        verify { udpClient.close() }
        verify { tcpClient.disconnect() }
        assertEquals(ConnectionState.Error("refused", ConnectionErrorKind.CONNECTION_REFUSED), repository.connectionState.first())
    }

    @Test
    fun `세션 토큰이 없으면 Move를 전송하지 않는다`() = runTest {
        repository.sendEvent(TrackpadEvent.Move(3f, 4f))

        coVerify(exactly = 0) { udpClient.send(any()) }
        coVerify(exactly = 0) { tcpClient.send(any()) }
    }

    @Test
    fun `disconnect는 UDP 소켓을 닫고 세션 토큰을 초기화한다`() = runTest {
        connectSuccessfully()
        // connect() 진입 시점에도 방어적으로 close()가 한 번 호출되므로(F-2 수정),
        // 여기서는 disconnect()가 유발하는 호출만 센다.
        clearMocks(udpClient, answers = false, recordedCalls = true)

        repository.disconnect()

        verify(exactly = 1) { udpClient.close() }
        verify(exactly = 1) { tcpClient.disconnect() }
        assertEquals(ConnectionState.Disconnected, repository.connectionState.first())

        // 세션 초기화 확인: 재연결 없이 보낸 MOVE는 전송되지 않는다
        repository.sendEvent(TrackpadEvent.Move(1f, 2f))
        coVerify(exactly = 0) { udpClient.send(any()) }
    }

    @Test
    fun `재연결 시 새 세션 토큰이 MOVE에 반영된다`() = runTest {
        connectSuccessfully()
        repository.disconnect()

        val newSession = "ffffffffffffffffffffffffffffffff"
        coEvery { tcpClient.connect(HOST, GestureConfig.DEFAULT_PORT, PIN) } returns newSession
        repository.connect(HOST, GestureConfig.DEFAULT_PORT, PIN)
        repository.sendEvent(TrackpadEvent.Move(0.5f, 0.5f))

        val json = slot<String>()
        coVerify(exactly = 1) { udpClient.send(capture(json)) }
        assertTrue(json.captured.contains(""""session":"$newSession""""))
    }

    @Test
    fun `connect 재시도 시작 시 이전 세션과 UDP 타깃을 즉시 무효화한다`() = runTest {
        // F-2: connect()가 핸드셰이크 완료를 기다리는 동안 구 토큰/구 UDP 타깃으로
        // MOVE가 새어나가지 않도록, 새 핸드셰이크 시도 전에 반드시 먼저 정리해야 한다.
        connectSuccessfully()
        clearMocks(udpClient, answers = false, recordedCalls = true)

        coEvery { tcpClient.connect(HOST, GestureConfig.DEFAULT_PORT, PIN) } returns
            "1111111111111111111111111111aaaa"
        repository.connect(HOST, GestureConfig.DEFAULT_PORT, PIN)

        coVerifyOrder {
            udpClient.close()
            udpClient.connect(HOST, GestureConfig.UDP_PORT)
        }
    }

    @Test
    fun `UDP 전송 실패는 연결 상태를 덮어쓰지 않는다`() = runTest {
        // F-2: MOVE는 고빈도 이벤트라 실패해도 조용히 버린다. 실제 연결 유실은
        // heartbeat watchdog이 감지해 Error로 전환하므로, 여기서 상태를 바꾸면
        // 그 원인 메시지(예: "Heartbeat timeout")를 지워버리게 된다.
        connectSuccessfully()
        coEvery { udpClient.send(any()) } throws java.io.IOException("udp down")

        repository.sendEvent(TrackpadEvent.Move(1f, 1f))

        assertEquals(ConnectionState.Connected(HOST), repository.connectionState.first())
    }

    @Test
    fun `SCROLL 전송 실패는 연결 상태를 덮어쓰지 않는다`() = runTest {
        // F-1(스크롤 QA): SCROLL도 MOVE처럼 드래그 중 연속으로 나가는 고빈도 이벤트라,
        // 실패해도 heartbeat watchdog이 이미 세팅한 Error 메시지를 덮어쓰면 안 된다.
        connectSuccessfully()
        coEvery { tcpClient.send(any()) } throws java.io.IOException("tcp down")

        repository.sendEvent(TrackpadEvent.Scroll(dx = 1, dy = 1))

        assertEquals(ConnectionState.Connected(HOST), repository.connectionState.first())
    }
}
