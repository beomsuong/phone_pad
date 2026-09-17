package com.example.phone_pad_app.data.repository

import com.example.phone_pad_app.data.network.TcpClient
import com.example.phone_pad_app.data.network.UdpClient
import com.example.phone_pad_app.domain.model.ConnectionState
import com.example.phone_pad_app.domain.model.TrackpadEvent
import com.example.phone_pad_app.presentation.util.GestureConfig
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import io.mockk.coVerifyOrder
import io.mockk.verify
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

private const val HOST = "192.168.0.10"
private const val SESSION = "0123456789abcdef0123456789abcdef"

class TrackpadRepositoryImplTest {

    private lateinit var tcpClient: TcpClient
    private lateinit var udpClient: UdpClient
    private lateinit var repository: TrackpadRepositoryImpl

    @Before
    fun setUp() {
        tcpClient = mockk(relaxed = true)
        udpClient = mockk(relaxed = true)
        repository = TrackpadRepositoryImpl(tcpClient, udpClient)
    }

    private suspend fun connectSuccessfully() {
        coEvery { tcpClient.connect(HOST, GestureConfig.DEFAULT_PORT) } returns SESSION
        repository.connect(HOST, GestureConfig.DEFAULT_PORT)
    }

    @Test
    fun `connect는 TCP 핸드셰이크 후 UDP 채널을 9001로 준비하고 Connected로 전환한다`() = runTest {
        connectSuccessfully()

        coVerify(exactly = 1) { tcpClient.connect(HOST, GestureConfig.DEFAULT_PORT) }
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
    fun `핸드셰이크가 오지 않으면 Error로 전환하고 UDP를 준비하지 않는다`() = runTest {
        coEvery { tcpClient.connect(HOST, GestureConfig.DEFAULT_PORT) } returns null

        repository.connect(HOST, GestureConfig.DEFAULT_PORT)

        coVerify(exactly = 0) { udpClient.connect(any(), any()) }
        verify { tcpClient.disconnect() }
        assertTrue(repository.connectionState.first() is ConnectionState.Error)
    }

    @Test
    fun `TCP 연결이 실패하면 Error로 전환하고 소켓을 정리한다`() = runTest {
        coEvery { tcpClient.connect(HOST, GestureConfig.DEFAULT_PORT) } throws
            java.net.ConnectException("refused")

        repository.connect(HOST, GestureConfig.DEFAULT_PORT)

        verify { udpClient.close() }
        verify { tcpClient.disconnect() }
        assertEquals(ConnectionState.Error("refused"), repository.connectionState.first())
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
        coEvery { tcpClient.connect(HOST, GestureConfig.DEFAULT_PORT) } returns newSession
        repository.connect(HOST, GestureConfig.DEFAULT_PORT)
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

        coEvery { tcpClient.connect(HOST, GestureConfig.DEFAULT_PORT) } returns
            "1111111111111111111111111111aaaa"
        repository.connect(HOST, GestureConfig.DEFAULT_PORT)

        coVerifyOrder {
            udpClient.close()
            udpClient.connect(HOST, GestureConfig.UDP_PORT)
        }
    }

    @Test
    fun `UDP 전송 실패는 Error 상태로 보고된다`() = runTest {
        connectSuccessfully()
        coEvery { udpClient.send(any()) } throws java.io.IOException("udp down")

        repository.sendEvent(TrackpadEvent.Move(1f, 1f))

        assertEquals(ConnectionState.Error("udp down"), repository.connectionState.first())
    }
}
