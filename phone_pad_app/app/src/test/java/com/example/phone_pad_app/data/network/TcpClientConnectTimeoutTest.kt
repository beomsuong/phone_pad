package com.example.phone_pad_app.data.network

import com.example.phone_pad_app.presentation.util.GestureConfig
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketAddress
import java.net.SocketTimeoutException
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * [TcpClient]의 연결 타임아웃 계약.
 *
 * **실제 5초를 기다리지 않는다.** `Socket`을 주입해 `connect(endpoint, timeout)` 인자를
 * 기록만 하고 즉시 예외로 빠져나오게 하거나, 루프백의 닫힌 포트로 즉시 실패시킨다.
 * (타임아웃이 실제로 만료되는 경로는 블랙홀 주소가 필요해 단위 테스트로 재현하지 않는다 —
 * 여기서 고정하는 것은 "OS 기본값에 맡기지 않고 우리 값을 넘긴다"는 계약이다.)
 */
class TcpClientConnectTimeoutTest {

    /** 연결 인자를 기록하고 즉시 타임아웃으로 실패하는 소켓. */
    private class RecordingSocket : Socket() {
        var recordedTimeout: Int? = null
        var recordedEndpoint: SocketAddress? = null

        override fun connect(endpoint: SocketAddress?, timeout: Int) {
            recordedEndpoint = endpoint
            recordedTimeout = timeout
            throw SocketTimeoutException("connect timed out")
        }
    }

    private lateinit var client: TcpClient
    private lateinit var recording: RecordingSocket

    @Before
    fun setUp() {
        client = TcpClient()
        recording = RecordingSocket()
    }

    @After
    fun tearDown() {
        client.disconnect()
        runCatching { recording.close() }
    }

    private fun connect(host: String = "127.0.0.1", port: Int = GestureConfig.DEFAULT_PORT) =
        runCatching { runBlocking { client.connect(host, port, PIN) } }

    @Test
    fun `연결은 GestureConfig의 CONNECT_TIMEOUT_MS로 제한된다`() {
        client.socketFactory = { recording }

        connect()

        // 핵심: OS 기본 타임아웃(Android는 20초 이상)에 맡기지 않는다.
        assertEquals(GestureConfig.CONNECT_TIMEOUT_MS, recording.recordedTimeout)
    }

    @Test
    fun `연결 대상은 입력한 host와 port다`() {
        client.socketFactory = { recording }

        connect(host = "127.0.0.1", port = 12345)

        val endpoint = recording.recordedEndpoint as InetSocketAddress
        assertEquals(12345, endpoint.port)
        assertEquals("127.0.0.1", endpoint.address?.hostAddress)
    }

    @Test
    fun `타임아웃 값은 테스트에서 짧게 바꿔 끼울 수 있다`() {
        // 실제 5초를 기다리는 테스트를 만들지 않기 위한 주입점이 살아 있는지 고정한다.
        client.connectTimeoutMs = 120
        client.socketFactory = { recording }

        connect()

        assertEquals(120, recording.recordedTimeout)
    }

    @Test
    fun `연결 타임아웃은 SocketTimeoutException으로 그대로 올라온다`() {
        // 리포지토리가 이 예외 타입으로 원인을 분류하므로 삼켜지면 안 된다.
        client.socketFactory = { recording }

        val result = connect()

        assertTrue(result.exceptionOrNull() is SocketTimeoutException)
    }

    @Test
    fun `연결에 실패하면 죽은 소켓을 남기지 않는다`() {
        client.socketFactory = { recording }

        connect()

        // 실패한 소켓이 필드에 남으면 isConnected가 거짓말을 하고, 이후 send/readLine이
        // "연결된 것처럼" 동작하려다 엉뚱한 예외를 낸다.
        assertFalse(client.isConnected)
        assertNull(client.soTimeoutMillis)
    }

    @Test
    fun `닫힌 포트로의 연결은 즉시 실패하고 클라이언트는 미연결로 남는다`() {
        // 실제 소켓 경로(주입 없이) 회귀 — Socket() + connect(InetSocketAddress, timeout)가
        // 기존 Socket(host, port)와 동일하게 즉시 실패한다.
        val probe = ServerSocket(0)
        val closedPort = probe.localPort
        probe.close()

        val result = connect(port = closedPort)

        assertTrue("닫힌 포트인데 연결이 성공했다", result.isFailure)
        assertFalse(client.isConnected)
    }

    @Test
    fun `실패한 연결 뒤에도 정상 연결이 가능하다`() {
        // 실패 경로가 필드를 잘못 정리하면 다음 연결이 조용히 망가진다.
        client.socketFactory = { recording }
        connect()

        val server = ServerSocket(0)
        val accepted = ArrayBlockingQueue<Socket>(1)
        Thread {
            runCatching {
                val s = server.accept()
                // PIN 인증 이후: 서버는 클라이언트의 AUTH 줄을 먼저 읽은 뒤 SESSION을 보낸다.
                BufferedReader(InputStreamReader(s.getInputStream(), Charsets.UTF_8)).readLine()
                PrintWriter(s.getOutputStream(), true).println(SESSION_LINE)
                accepted.put(s)
            }
        }.apply { isDaemon = true }.start()

        try {
            client.socketFactory = { Socket() }
            val session = runBlocking { client.connect("127.0.0.1", server.localPort, PIN) }

            assertEquals(SESSION_TOKEN, session)
            assertTrue(client.isConnected)
            assertEquals(GestureConfig.HEARTBEAT_INTERVAL_MS.toInt(), client.soTimeoutMillis)
        } finally {
            runCatching { accepted.poll(5, TimeUnit.SECONDS)?.close() }
            runCatching { server.close() }
        }
    }

    private companion object {
        const val SESSION_TOKEN = "0123456789abcdef0123456789abcdef"
        const val SESSION_LINE = """{"type": "SESSION", "session": "$SESSION_TOKEN"}"""

        /** 인증이 꺼진 서버를 모사하는 테스트들이라 값은 무관하다. */
        const val PIN = "483920"
    }
}
