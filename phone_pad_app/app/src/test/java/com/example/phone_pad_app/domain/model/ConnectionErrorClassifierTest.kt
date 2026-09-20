package com.example.phone_pad_app.domain.model

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.PortUnreachableException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * 연결 실패 예외 → [ConnectionErrorKind] 분류 (순수 함수, 소켓/코루틴 없음).
 *
 * 메시지 문자열은 실제 JVM/Android에서 관측되는 형태를 그대로 쓴다 — 분류가 특정 플랫폼의
 * 문구에만 맞춰지지 않았는지 확인하기 위함이다.
 */
class ConnectionErrorClassifierTest {

    private fun classify(t: Throwable?) = ConnectionErrorClassifier.classify(t)

    @Test
    fun `연결 거부는 CONNECTION_REFUSED다`() {
        assertEquals(
            ConnectionErrorKind.CONNECTION_REFUSED,
            classify(ConnectException("Connection refused")),
        )
    }

    @Test
    fun `메시지 없는 ConnectException도 거부로 본다`() {
        // ECONNREFUSED가 압도적으로 흔하고, 조치("서버가 켜져 있는지 확인")도 가장 유용하다.
        assertEquals(ConnectionErrorKind.CONNECTION_REFUSED, classify(ConnectException()))
    }

    @Test
    fun `연결 타임아웃은 TIMEOUT이다`() {
        assertEquals(ConnectionErrorKind.TIMEOUT, classify(SocketTimeoutException()))
        // Android가 connect 타임아웃에 실제로 쓰는 문구
        assertEquals(
            ConnectionErrorKind.TIMEOUT,
            classify(
                SocketTimeoutException(
                    "failed to connect to /192.168.0.5 (port 9000) from /:: (port 41822) after 5000ms"
                )
            ),
        )
    }

    @Test
    fun `주소 해석 실패는 UNKNOWN_HOST다`() {
        assertEquals(
            ConnectionErrorKind.UNKNOWN_HOST,
            classify(UnknownHostException("Unable to resolve host \"phone-pad\"")),
        )
    }

    @Test
    fun `라우팅 불가와 포트 도달 불가는 NETWORK_UNREACHABLE이다`() {
        assertEquals(
            ConnectionErrorKind.NETWORK_UNREACHABLE,
            classify(NoRouteToHostException("No route to host")),
        )
        assertEquals(
            ConnectionErrorKind.NETWORK_UNREACHABLE,
            classify(PortUnreachableException()),
        )
    }

    @Test
    fun `네트워크 없음은 타입이 아니라 메시지로도 잡힌다`() {
        // Wi-Fi가 꺼진 Android에서 흔히 오는 모양 — 타입은 평범한 SocketException이다.
        assertEquals(
            ConnectionErrorKind.NETWORK_UNREACHABLE,
            classify(SocketException("Network is unreachable")),
        )
        // ConnectException이 unreachable을 담고 오는 경우, 타입(거부)보다 메시지가 구체적이다.
        assertEquals(
            ConnectionErrorKind.NETWORK_UNREACHABLE,
            classify(ConnectException("Network is unreachable")),
        )
    }

    @Test
    fun `원인 체인에 단서가 있으면 따라 내려가 찾는다`() {
        val wrapped = IOException("connect failed", ConnectException("Connection refused"))

        assertEquals(ConnectionErrorKind.CONNECTION_REFUSED, classify(wrapped))
    }

    @Test
    fun `자기 자신을 원인으로 갖는 예외에서도 멈춘다`() {
        // 분류기가 무한 루프에 빠지면 연결 실패 하나가 앱을 멈춰 세운다.
        val looping = object : IOException("weird") {
            override val cause: Throwable get() = this
        }

        assertEquals(ConnectionErrorKind.UNKNOWN, classify(looping))
    }

    @Test
    fun `단서가 없으면 UNKNOWN이다`() {
        assertEquals(ConnectionErrorKind.UNKNOWN, classify(null))
        assertEquals(ConnectionErrorKind.UNKNOWN, classify(IOException()))
        assertEquals(ConnectionErrorKind.UNKNOWN, classify(IllegalStateException("Not connected")))
    }

    @Test
    fun `메시지만 있는 경로도 같은 규칙으로 분류한다`() {
        assertEquals(
            ConnectionErrorKind.CONNECTION_REFUSED,
            ConnectionErrorClassifier.classifyMessage("Connection refused"),
        )
        assertEquals(
            ConnectionErrorKind.UNKNOWN,
            ConnectionErrorClassifier.classifyMessage(null),
        )
        assertEquals(
            ConnectionErrorKind.UNKNOWN,
            ConnectionErrorClassifier.classifyMessage(""),
        )
    }
}
