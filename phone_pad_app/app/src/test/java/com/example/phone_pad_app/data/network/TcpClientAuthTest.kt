package com.example.phone_pad_app.data.network

import com.example.phone_pad_app.domain.model.AuthFailedException
import com.example.phone_pad_app.domain.model.SessionReplacedException
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
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * PIN 인증 핸드셰이크의 소켓 레벨 계약 (루프백, Android 프레임워크 없음).
 *
 * 서버 역할 fake는 **확정 스펙 그대로** 동작한다: 연결을 수락해도 아무것도 보내지 않고 기다리다가,
 * 클라이언트의 AUTH 한 줄을 받은 뒤에야 SESSION 또는 AUTH_FAIL을 보낸다. 그래서 이 파일은
 * "클라이언트가 먼저 말하지 않으면 영원히 조용한 서버"에서도 연결이 성립하는지를 검증한다 —
 * 서버가 먼저 SESSION을 보내던 구버전에서는 성립했지만 새 서버에서는 실패하는 구현을 잡아낸다.
 */
class TcpClientAuthTest {

    private lateinit var serverSocket: ServerSocket
    private lateinit var client: TcpClient

    private val accepted = ArrayBlockingQueue<Socket>(1)
    private val firstLineFromClient = ArrayBlockingQueue<String>(1)

    @Before
    fun setUp() {
        serverSocket = ServerSocket(0)
        client = TcpClient()
    }

    @After
    fun tearDown() {
        client.disconnect()
        runCatching { accepted.poll()?.close() }
        runCatching { serverSocket.close() }
    }

    /**
     * AUTH 줄을 **기다린 뒤** [response]를 보내는 서버를 띄운다.
     *
     * @param response 보낼 한 줄. null이면 아무것도 보내지 않고 연결을 닫는다
     *   (서버가 형식 오류 등으로 "조용히 닫는" 경로).
     */
    private fun startServer(response: String?) {
        Thread {
            runCatching {
                val s = serverSocket.accept()
                val auth = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.UTF_8))
                    .readLine()
                if (auth != null) firstLineFromClient.put(auth)
                if (response != null) {
                    PrintWriter(s.getOutputStream(), true).println(response)
                    accepted.put(s)
                } else {
                    s.close()
                }
            }
        }.apply { isDaemon = true }.start()
    }

    private fun connect(pin: String = PIN) =
        runCatching { runBlocking { client.connect("127.0.0.1", serverSocket.localPort, pin) } }

    @Test
    fun `서버가 먼저 말하지 않아도 AUTH를 보내고 SESSION을 받는다`() {
        startServer(SESSION_LINE)

        val result = connect()

        assertEquals(SESSION_TOKEN, result.getOrNull())
        assertEquals("""{"type":"AUTH","pin":"$PIN"}""", firstLineFromClient.poll(5, TimeUnit.SECONDS))
        assertTrue(client.isConnected)
    }

    @Test
    fun `AUTH_FAIL을 받으면 AuthFailedException을 던진다`() {
        startServer("""{"type":"AUTH_FAIL","reason":"invalid_pin"}""")

        val result = connect(pin = "000000")

        val error = result.exceptionOrNull()
        assertTrue("실제 예외: $error", error is AuthFailedException)
        // 일반 핸드셰이크 실패(null 반환)와 구분되어야 사용자에게 "PIN을 확인하세요"라고 말할 수 있다.
        assertNull(result.getOrNull())
    }

    @Test
    fun `AUTH_FAIL의 reason은 진단 메시지에 담긴다`() {
        startServer("""{"type":"AUTH_FAIL","reason":"invalid_pin"}""")

        val error = connect().exceptionOrNull() as AuthFailedException

        assertEquals("invalid_pin", error.reason)
        assertTrue(error.message!!.contains("invalid_pin"))
    }

    @Test
    fun `AUTH_FAIL 뒤에는 죽은 소켓을 남기지 않는다`() {
        // 서버는 AUTH_FAIL을 보낸 직후 연결을 닫는다. 이쪽이 소켓을 붙들고 있으면
        // isConnected가 거짓말을 하고 이후 send가 엉뚱한 예외를 낸다.
        startServer("""{"type":"AUTH_FAIL","reason":"invalid_pin"}""")

        connect()

        assertFalse(client.isConnected)
        assertNull(client.soTimeoutMillis)
    }

    @Test
    fun `인증이 꺼진 서버는 PIN 값과 무관하게 SESSION을 준다`() {
        // 클라이언트는 서버 설정을 알 수 없으므로 언제나 같은 형식을 보낸다(확정 스펙).
        startServer(SESSION_LINE)

        val result = connect(pin = "")

        assertEquals(SESSION_TOKEN, result.getOrNull())
        assertEquals("""{"type":"AUTH","pin":""}""", firstLineFromClient.poll(5, TimeUnit.SECONDS))
    }

    @Test
    fun `SESSION_REPLACED를 핸드셰이크로 받으면 SessionReplacedException을 던진다`() {
        // protocol-qa W-1: 우리가 SESSION을 받기 전에 세 번째 기기가 우리를 다시 밀어낸
        // 극히 드문 경합. null(=HANDSHAKE_FAILED, "서버가 아님")과 섞이면 안 된다.
        startServer("""{"type":"SESSION_REPLACED"}""")

        val result = connect()

        assertTrue(
            "실제 예외: ${result.exceptionOrNull()}",
            result.exceptionOrNull() is SessionReplacedException,
        )
        assertNull(result.getOrNull())
    }

    @Test
    fun `AUTH_FAIL도 SESSION도 아닌 줄은 기존처럼 핸드셰이크 실패다`() {
        // 예외가 아니라 null — "PIN이 틀렸다"와 "Phone Pad 서버가 아니다"는 다른 조치를 부른다.
        startServer("""{"type":"HELLO"}""")

        val result = connect()

        assertNull(result.getOrNull())
        assertNull(result.exceptionOrNull())
    }

    @Test
    fun `서버가 조용히 끊으면 기존처럼 핸드셰이크 실패다`() {
        // 형식 오류나 브루트포스 잠금에서 서버가 아무 응답 없이 닫는 경로(확정 스펙).
        startServer(response = null)

        val result = connect()

        // EOF는 예외가 아니라 null 핸드셰이크다 — 소켓 정리는 호출자(리포지토리)가 한다.
        assertNull(result.getOrNull())
        assertNull(result.exceptionOrNull())
    }

    private companion object {
        const val PIN = "483920"
        const val SESSION_TOKEN = "0123456789abcdef0123456789abcdef"
        const val SESSION_LINE = """{"type": "SESSION", "session": "$SESSION_TOKEN"}"""
    }
}
