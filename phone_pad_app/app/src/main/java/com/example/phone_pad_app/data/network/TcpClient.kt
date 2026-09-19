package com.example.phone_pad_app.data.network

import com.example.phone_pad_app.presentation.util.GestureConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.Socket
import javax.inject.Inject
import javax.inject.Singleton

/**
 * TCP 채널 (기본 9000).
 * - 연결 직후 서버가 보내는 세션 핸드셰이크 한 줄을 읽어 세션 토큰을 확보한다.
 * - MOVE 이외의 이벤트(CLICK/SCROLL/DRAG/HEARTBEAT)를 newline-delimited JSON으로 전송한다.
 */
@Singleton
class TcpClient @Inject constructor() {

    private var socket: Socket? = null
    private var writer: PrintWriter? = null
    private var reader: BufferedReader? = null

    /**
     * [send]를 이 디스패처(병렬도 1)로 직렬화한다.
     *
     * 이벤트마다 별도 코루틴에서 [send]를 호출하면(`TrackpadViewModel`이 이벤트 종류별로
     * 각각 `launch`한다) 공용 `Dispatchers.IO`(병렬도 64)에서는 실행 순서가 뒤바뀔 수 있다.
     * DRAG_START 직후 곧바로 DRAG_END를 보내는 경우처럼 **순서가 의미를 갖는 이벤트 쌍**에서
     * 이 역전이 일어나면, 서버는 DRAG_END(아직 비활성 → 무시) 다음에 DRAG_START(LEFTDOWN)를
     * 받아 마우스 버튼이 눌린 채로 남는다. 단일 스레드로 직렬화하면 호출된 순서대로
     * 소켓에 쓰기가 실행된다.
     */
    private val sendDispatcher = Dispatchers.IO.limitedParallelism(1)

    /**
     * 서버에 연결하고 세션 핸드셰이크 한 줄을 읽는다.
     *
     * @return 서버가 발급한 세션 토큰. 핸드셰이크가 오지 않거나 형식이 어긋나면 null.
     */
    suspend fun connect(host: String, port: Int): String? = withContext(Dispatchers.IO) {
        disconnect()
        val s = Socket(host, port)
        socket = s
        writer = PrintWriter(s.getOutputStream(), true)
        val r = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.UTF_8))
        reader = r

        s.soTimeout = GestureConfig.SESSION_HANDSHAKE_TIMEOUT_MS
        val session = SessionHandshake.parseSession(r.readLine())
        if (session != null) {
            // 핸드셰이크 성공 후에는 heartbeat 주기를 읽기 타임아웃으로 사용한다.
            // 읽기 한 번이 이 시간 안에 아무것도 받지 못하면 미응답 1회로 집계된다.
            applyHeartbeatTimeout(s)
        }
        session
    }

    /**
     * 소켓 읽기 타임아웃을 [GestureConfig.HEARTBEAT_INTERVAL_MS]로 맞춘다.
     * 이후 [readLine]은 이 시간 안에 한 줄을 못 받으면 [java.net.SocketTimeoutException]을 던진다.
     */
    fun applyHeartbeatTimeout() {
        socket?.let { applyHeartbeatTimeout(it) }
    }

    private fun applyHeartbeatTimeout(s: Socket) {
        s.soTimeout = GestureConfig.HEARTBEAT_INTERVAL_MS.toInt()
    }

    /**
     * 연결 유지 중 TCP에서 한 줄을 읽는다 (heartbeat ACK 등).
     *
     * - 정상 수신: 개행을 제외한 한 줄
     * - 스트림 종료(EOF, 서버가 먼저 끊음): null
     * - 타임아웃: [java.net.SocketTimeoutException]을 **그대로 던진다** —
     *   미응답 카운트 판정은 호출자([com.example.phone_pad_app.data.repository.TrackpadRepositoryImpl])의 책임이다.
     */
    suspend fun readLine(): String? = withContext(Dispatchers.IO) {
        checkNotNull(reader) { "Not connected" }.readLine()
    }

    suspend fun send(json: String) = withContext(sendDispatcher) {
        checkNotNull(writer) { "Not connected" }.println(json)
    }

    fun disconnect() {
        runCatching { reader?.close() }
        writer?.close()
        runCatching { socket?.close() }
        reader = null
        writer = null
        socket = null
    }

    /** 현재 소켓의 읽기 타임아웃 (ms). 연결되어 있지 않으면 null. */
    val soTimeoutMillis: Int?
        get() = socket?.soTimeout

    val isConnected: Boolean
        get() = socket?.let { !it.isClosed && it.isConnected } ?: false
}
