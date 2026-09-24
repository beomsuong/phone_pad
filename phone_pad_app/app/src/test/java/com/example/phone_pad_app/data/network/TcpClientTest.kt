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
 *
 * **PIN 인증(Phase 5) 이후 서버 역할 fake가 바뀌었다:** 예전에는 연결을 수락하자마자 SESSION을
 * 보냈지만, 이제는 클라이언트의 AUTH 줄을 **먼저 읽은 뒤** 응답한다. 인증이 꺼진 서버를
 * 모사하므로 `pin` 값은 검사하지 않는다(값 검증은 별도 테스트).
 */
class TcpClientTest {

    private lateinit var serverSocket: ServerSocket
    private lateinit var client: TcpClient
    private val accepted = ArrayBlockingQueue<Socket>(1)

    /** 서버가 실제로 받은 첫 줄 (AUTH 검증용). */
    private val firstLineFromClient = ArrayBlockingQueue<String>(1)

    /**
     * 서버 쪽 읽기 스트림. 첫 줄(AUTH)을 읽은 그 리더를 이후 테스트에서도 **그대로 재사용한다** —
     * 같은 소켓에 리더를 새로 만들면 첫 리더의 버퍼에 남은 바이트를 잃을 수 있다.
     */
    private val serverReaders = ArrayBlockingQueue<BufferedReader>(1)

    @Before
    fun setUp() {
        serverSocket = ServerSocket(0)
        client = TcpClient()
        Thread {
            runCatching {
                val s = serverSocket.accept()
                // 서버는 AUTH 한 줄을 받은 뒤에 비로소 응답한다 (확정 스펙).
                val reader = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.UTF_8))
                val auth = reader.readLine()
                serverReaders.put(reader)
                if (auth != null) firstLineFromClient.put(auth)
                // 세션 한 줄 (json.dumps 기본형 = 콜론 뒤 공백)
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
        client.connect("127.0.0.1", serverSocket.localPort, PIN)
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
        accepted.poll(5, TimeUnit.SECONDS)!!
        val serverReader = serverReaders.poll(5, TimeUnit.SECONDS)!!

        runBlocking { client.send("""{"type":"HEARTBEAT"}""") }

        assertEquals("""{"type":"HEARTBEAT"}""", serverReader.readLine())
    }

    @Test
    fun `클라이언트가 보내는 첫 줄은 AUTH다`() {
        // 확정 스펙의 핵심 순서: 서버는 AUTH 줄을 받기 전에 아무것도 보내지 않으므로,
        // 이 줄이 먼저 나가지 않으면 모든 연결이 핸드셰이크 타임아웃으로 실패한다.
        connect()

        assertEquals(
            """{"type":"AUTH","pin":"$PIN"}""",
            firstLineFromClient.poll(5, TimeUnit.SECONDS),
        )
    }

    @Test
    fun `AUTH 다음 줄이 이벤트로 나간다 - AUTH가 두 번 나가지 않는다`() {
        connect()
        accepted.poll(5, TimeUnit.SECONDS)!!
        val serverReader = serverReaders.poll(5, TimeUnit.SECONDS)!!

        runBlocking { client.send("""{"type":"HEARTBEAT"}""") }

        // AUTH는 연결 1회당 정확히 한 번이다. 두 번 나가면 서버가 그 줄을 이벤트로 읽는다.
        assertEquals("""{"type":"HEARTBEAT"}""", serverReader.readLine())
    }

    private companion object {
        /** 인증이 꺼진 서버를 모사하므로 값 자체는 무관하다 — 형식만 고정한다. */
        const val PIN = "483920"
    }
}
