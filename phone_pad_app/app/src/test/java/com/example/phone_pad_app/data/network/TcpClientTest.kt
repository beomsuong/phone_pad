package com.example.phone_pad_app.data.network

import com.example.phone_pad_app.presentation.util.GestureConfig
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * 루프백 소켓 기반 [TcpClient] 검증 (Android 프레임워크 의존 없음).
 * heartbeat 관련 계약: 핸드셰이크 성공 후 soTimeout 전환, [TcpClient.readLine] 동작.
 */
class TcpClientTest {

    private lateinit var serverSocket: ServerSocket
    private lateinit var client: TcpClient
    private val accepted = ArrayBlockingQueue<Socket>(1)

    @Before
    fun setUp() {
        serverSocket = ServerSocket(0)
        client = TcpClient()
        Thread {
            runCatching {
                val s = serverSocket.accept()
                // 서버는 다른 어떤 이벤트보다 먼저 세션 한 줄을 보낸다 (json.dumps 기본형 = 콜론 뒤 공백)
                PrintWriter(s.getOutputStream(), true)
                    .println("""{"type": "SESSION", "session": "0123456789abcdef0123456789abcdef"}""")
                accepted.put(s)
            }
        }.apply { isDaemon = true }.start()
    }

    @After
    fun tearDown() {
        client.disconnect()
        runCatching { accepted.poll()?.close() }
        runCatching { serverSocket.close() }
    }

    private fun connect(): String? = runBlocking {
        client.connect("127.0.0.1", serverSocket.localPort)
    }

    @Test
    fun `핸드셰이크 성공 후 soTimeout이 HEARTBEAT_INTERVAL_MS로 바뀐다`() {
        val session = connect()

        assertEquals("0123456789abcdef0123456789abcdef", session)
        assertEquals(
            GestureConfig.HEARTBEAT_INTERVAL_MS.toInt(),
            client.soTimeoutMillis
        )
    }

    @Test
    fun `readLine은 서버가 보낸 줄을 개행 없이 그대로 돌려준다`() {
        connect()
        val server = accepted.poll(5, TimeUnit.SECONDS)!!
        PrintWriter(server.getOutputStream(), true).println("""{"type":"HEARTBEAT_ACK"}""")

        val line = runBlocking { client.readLine() }

        assertEquals("""{"type":"HEARTBEAT_ACK"}""", line)
    }

    @Test
    fun `서버가 먼저 끊으면 readLine은 EOF로 null을 돌려준다`() {
        connect()
        val server = accepted.poll(5, TimeUnit.SECONDS)!!
        server.close()

        assertNull(runBlocking { client.readLine() })
    }

    @Test
    fun `send는 newline 종료 JSON 한 줄을 그대로 내보낸다`() {
        connect()
        val server = accepted.poll(5, TimeUnit.SECONDS)!!
        val serverReader = BufferedReader(InputStreamReader(server.getInputStream(), Charsets.UTF_8))

        runBlocking { client.send("""{"type":"HEARTBEAT"}""") }

        assertEquals("""{"type":"HEARTBEAT"}""", serverReader.readLine())
    }
}
