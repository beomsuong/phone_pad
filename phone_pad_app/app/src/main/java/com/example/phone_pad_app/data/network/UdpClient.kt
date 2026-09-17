package com.example.phone_pad_app.data.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import javax.inject.Inject
import javax.inject.Singleton

/**
 * UDP 채널 (기본 9001) — MOVE 이벤트 전용.
 *
 * 패킷 하나 = 이벤트 하나이며 개행을 붙이지 않는다.
 * 세션 토큰은 상위 계층([com.example.phone_pad_app.data.repository.TrackpadRepositoryImpl])이
 * JSON에 포함시켜 넘긴다.
 */
@Singleton
class UdpClient @Inject constructor() {

    private var socket: DatagramSocket? = null
    private var address: InetAddress? = null
    private var port: Int = 0

    /** 대상 호스트를 해석하고 송신용 소켓을 준비한다 (UDP는 연결 개념이 없으므로 핸드셰이크 없음). */
    suspend fun connect(host: String, port: Int) = withContext(Dispatchers.IO) {
        close()
        address = InetAddress.getByName(host)
        this@UdpClient.port = port
        socket = DatagramSocket()
    }

    /** MOVE JSON 한 덩어리를 UDP 패킷 하나로 보낸다. */
    suspend fun send(json: String) = withContext(Dispatchers.IO) {
        val s = checkNotNull(socket) { "UDP not ready" }
        val target = checkNotNull(address) { "UDP not ready" }
        val bytes = json.toByteArray(Charsets.UTF_8)
        s.send(DatagramPacket(bytes, bytes.size, target, port))
    }

    fun close() {
        socket?.close()
        socket = null
        address = null
        port = 0
    }

    val isReady: Boolean
        get() = socket?.let { !it.isClosed } ?: false
}
