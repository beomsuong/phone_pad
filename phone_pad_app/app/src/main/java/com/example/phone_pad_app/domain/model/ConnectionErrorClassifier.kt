package com.example.phone_pad_app.domain.model

import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.PortUnreachableException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * 연결 실패 [Throwable] → [ConnectionErrorKind] 분류기.
 *
 * **순수 함수만 있다** — Android 프레임워크/코루틴/소켓에 의존하지 않으므로 평범한 JUnit
 * 테스트로 전 분기를 고정할 수 있다.
 *
 * 타입만으로 판정하지 않고 메시지 키워드도 함께 보는 이유: 같은 실패가 플랫폼마다 다른
 * 예외로 온다. 예를 들어 연결 타임아웃은 JVM에서는 [SocketTimeoutException]이지만 Android는
 * `SocketTimeoutException: failed to connect to /192.168.0.5 (port 9000) ... after 21000ms`처럼
 * 메시지에만 단서가 있는 경우가 있고, "Network is unreachable"은 [ConnectException]이나
 * 평범한 `SocketException`으로도 온다. 타입 → 키워드 → 폴백 순으로 좁힌다.
 */
object ConnectionErrorClassifier {

    /** 원인 체인을 따라갈 최대 깊이. 순환 참조(자기 자신을 cause로 갖는 예외)에서도 멈춘다. */
    private const val MAX_CAUSE_DEPTH = 5

    /**
     * [throwable]과 그 원인 체인을 훑어 가장 먼저 특정되는 종류를 돌려준다.
     *
     * null이거나 아무 단서도 없으면 [ConnectionErrorKind.UNKNOWN] — 이 경우 표시 계층이
     * 예외 원문을 그대로 덧붙여 보여주므로 정보가 사라지지는 않는다.
     */
    fun classify(throwable: Throwable?): ConnectionErrorKind {
        var current: Throwable? = throwable
        var depth = 0
        while (current != null && depth < MAX_CAUSE_DEPTH) {
            val kind = classifyOne(current)
            if (kind != ConnectionErrorKind.UNKNOWN) return kind
            val next = current.cause
            if (next === current) return ConnectionErrorKind.UNKNOWN
            current = next
            depth += 1
        }
        return ConnectionErrorKind.UNKNOWN
    }

    /**
     * 예외 없이 **메시지만** 남은 경로용(예: 재연결 루프가 실패 원인을 문자열로만 들고 있을 때).
     * 키워드가 없으면 [ConnectionErrorKind.UNKNOWN].
     */
    fun classifyMessage(message: String?): ConnectionErrorKind = fromMessage(message)

    private fun classifyOne(throwable: Throwable): ConnectionErrorKind {
        // 타입이 확실한 것부터. SocketTimeoutException은 ConnectException의 형제이므로 순서 무관.
        val byType = when (throwable) {
            // 가장 먼저 본다: 서버가 명시적으로 거부한 경우이므로 다른 어떤 단서보다 확실하다.
            // (IOException 계열이라 메시지 추측 경로로 흘러가면 엉뚱하게 분류될 수 있다.)
            is AuthFailedException -> ConnectionErrorKind.AUTH_FAILED
            // AuthFailedException과 같은 우선순위 — 둘 다 서버가 명시적으로 보낸 형식 있는
            // 응답이라 메시지 키워드 추측 경로로 흘러가면 안 된다.
            is SessionReplacedException -> ConnectionErrorKind.SESSION_REPLACED
            is SocketTimeoutException -> ConnectionErrorKind.TIMEOUT
            is UnknownHostException -> ConnectionErrorKind.UNKNOWN_HOST
            is NoRouteToHostException -> ConnectionErrorKind.NETWORK_UNREACHABLE
            is PortUnreachableException -> ConnectionErrorKind.NETWORK_UNREACHABLE
            else -> ConnectionErrorKind.UNKNOWN
        }
        if (byType != ConnectionErrorKind.UNKNOWN) return byType

        // 메시지 단서가 타입보다 구체적일 수 있다 (ConnectException이 "unreachable"을 담는 경우).
        val byMessage = fromMessage(throwable.message)
        if (byMessage != ConnectionErrorKind.UNKNOWN) return byMessage

        // 단서가 없는 ConnectException은 거부로 본다 — 실제로 대부분 ECONNREFUSED다.
        return if (throwable is ConnectException) {
            ConnectionErrorKind.CONNECTION_REFUSED
        } else {
            ConnectionErrorKind.UNKNOWN
        }
    }

    private fun fromMessage(message: String?): ConnectionErrorKind {
        val text = message?.lowercase() ?: return ConnectionErrorKind.UNKNOWN
        return when {
            text.contains("refused") -> ConnectionErrorKind.CONNECTION_REFUSED
            text.contains("unreachable") -> ConnectionErrorKind.NETWORK_UNREACHABLE
            text.contains("no route to host") -> ConnectionErrorKind.NETWORK_UNREACHABLE
            text.contains("timed out") -> ConnectionErrorKind.TIMEOUT
            text.contains("timeout") -> ConnectionErrorKind.TIMEOUT
            text.contains("unable to resolve host") -> ConnectionErrorKind.UNKNOWN_HOST
            else -> ConnectionErrorKind.UNKNOWN
        }
    }
}
