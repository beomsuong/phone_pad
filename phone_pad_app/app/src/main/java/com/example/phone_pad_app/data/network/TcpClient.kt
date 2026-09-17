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

        val previousTimeout = s.soTimeout
        try {
            s.soTimeout = GestureConfig.SESSION_HANDSHAKE_TIMEOUT_MS
            SessionHandshake.parseSession(r.readLine())
        } finally {
            // 이후 수신(heartbeat ack 등)은 기본 타임아웃 정책으로 되돌린다.
            runCatching { s.soTimeout = previousTimeout }
        }
    }

    suspend fun send(json: String) = withContext(Dispatchers.IO) {
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

    val isConnected: Boolean
        get() = socket?.let { !it.isClosed && it.isConnected } ?: false
}
