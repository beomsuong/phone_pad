package com.example.phone_pad_app.data.network

import com.example.phone_pad_app.domain.model.AuthFailedException
import com.example.phone_pad_app.presentation.util.GestureConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.InetSocketAddress
import java.net.Socket
import javax.inject.Inject
import javax.inject.Singleton

/**
 * TCP 채널 (기본 9000).
 * - 연결 직후 **먼저 AUTH 한 줄을 보내고**([AuthHandshake]), 서버가 그 응답으로 주는 세션
 *   핸드셰이크 한 줄을 읽어 세션 토큰을 확보한다. PIN이 틀리면 서버는 `AUTH_FAIL` 한 줄을
 *   보내고 연결을 닫는다.
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
     * 연결 시도 타임아웃 (ms). 기본값은 [GestureConfig.CONNECT_TIMEOUT_MS].
     *
     * `internal var`인 이유는 **테스트에서만** 짧게 줄이기 위해서다 — 실제 5초를 기다리는
     * 단위 테스트를 만들지 않기 위한 주입점이며, 프로덕션 코드는 이 값을 건드리지 않는다.
     */
    internal var connectTimeoutMs: Int = GestureConfig.CONNECT_TIMEOUT_MS

    /**
     * 소켓 생성 지점. 테스트에서 연결 인자(특히 타임아웃)를 기록하거나 특정 예외를 재현하기
     * 위한 주입점이다. 프로덕션에서는 항상 기본 [Socket] 생성자를 쓴다.
     */
    internal var socketFactory: () -> Socket = { Socket() }

    /**
     * 서버에 연결하고, **AUTH 한 줄을 먼저 보낸 뒤** 세션 핸드셰이크 한 줄을 읽는다.
     *
     * 순서가 계약이다 (확정 스펙): 서버는 클라이언트의 AUTH 줄을 받기 전에는 아무것도 보내지
     * 않으므로, AUTH를 보내기 전에 읽으려 하면 반드시 핸드셰이크 타임아웃으로 실패한다.
     * 그래서 소켓이 붙은 직후 다른 어떤 읽기/쓰기보다 먼저 AUTH를 내보낸다.
     *
     * 연결은 [connectTimeoutMs] 안에 끝나야 한다 — 넘으면 [java.net.SocketTimeoutException].
     * 주소를 해석할 수 없으면 [java.net.UnknownHostException].
     *
     * **취소 가능성:** 블로킹 `connect()`는 코루틴 취소로 풀리지 않는다. 그래서 소켓을
     * 연결 시도 **전에** [socket] 필드에 등록해, 다른 코루틴이 [disconnect]로 소켓을 닫아
     * 진행 중인 시도를 깨울 수 있게 한다(첫 연결 "취소" 버튼이 이 경로를 쓴다).
     * 그때 이 함수는 `SocketException`으로 빠져나온다.
     *
     * @param pin 사용자가 입력한 PIN. 서버 인증이 꺼져 있어도 **항상** 보낸다(와이어 형식이
     *   서버 설정에 따라 갈라지지 않게 하기 위해 — [AuthHandshake] 참조).
     * @return 서버가 발급한 세션 토큰. 핸드셰이크가 오지 않거나 형식이 어긋나면 null.
     * @throws AuthFailedException 서버가 PIN 불일치로 `AUTH_FAIL`을 보낸 경우. null 반환
     *   (= 일반 핸드셰이크 실패)과 구분해야 사용자에게 "PIN을 확인하세요"라고 말할 수 있다.
     */
    suspend fun connect(host: String, port: Int, pin: String): String? = withContext(Dispatchers.IO) {
        disconnect()
        val s = socketFactory()
        socket = s
        try {
            s.connect(InetSocketAddress(host, port), connectTimeoutMs)
            val w = PrintWriter(s.getOutputStream(), true)
            writer = w
            val r = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.UTF_8))
            reader = r

            // 서버가 응답을 시작하는 트리거. 반드시 첫 읽기보다 먼저 나가야 한다.
            w.println(AuthHandshake.buildAuthLine(pin))

            s.soTimeout = GestureConfig.SESSION_HANDSHAKE_TIMEOUT_MS
            val line = r.readLine()
            if (AuthHandshake.isAuthFail(line)) {
                // 형식이 맞는 거부 응답이다 — "서버가 아예 응답하지 않음"과 섞지 않는다.
                throw AuthFailedException(AuthHandshake.parseFailReason(line))
            }
            val session = SessionHandshake.parseSession(line)
            if (session != null) {
                // 핸드셰이크 성공 후에는 heartbeat 주기를 읽기 타임아웃으로 사용한다.
                // 읽기 한 번이 이 시간 안에 아무것도 받지 못하면 미응답 1회로 집계된다.
                applyHeartbeatTimeout(s)
            }
            session
        } catch (e: Throwable) {
            // 실패한 소켓을 필드에 남겨 두면 isConnected/soTimeoutMillis가 거짓말을 한다.
            // 이미 다른 코루틴이 우리를 취소하며 필드를 비웠을 수도 있으므로(=== 검사),
            // 우리가 만든 소켓만 직접 닫는다.
            runCatching { s.close() }
            if (socket === s) disconnect()
            throw e
        }
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
