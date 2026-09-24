package com.example.phone_pad_app.presentation.util

import com.example.phone_pad_app.domain.model.ConnectionErrorKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 표시 계층의 한국어 문구 매핑 (순수 함수).
 *
 * 문구 전문을 리터럴로 고정하지는 않는다 — 문장은 다듬을 수 있어야 한다. 대신 **계약**만
 * 고정한다: 모든 종류가 문구를 갖는다 / 예외 원문이 주 메시지를 점령하지 않는다 /
 * 원문을 완전히 버리지 않는다 / 각 종류가 해당하는 조치 힌트를 담는다.
 */
class ConnectionErrorMessagesTest {

    private val raw = "failed to connect to /192.168.0.5 (port 9000) after 5000ms"

    @Test
    fun `모든 원인 종류가 비어 있지 않은 한국어 문구를 갖는다`() {
        // enum에 값을 추가하고 문구를 빠뜨리면 여기서 걸린다.
        ConnectionErrorKind.values().forEach { kind ->
            val message = ConnectionErrorMessages.userMessage(kind, raw)
            assertTrue("$kind 문구가 비어 있다", message.isNotBlank())
            assertTrue("$kind 문구에 한글이 없다", message.any { it in '가'..'힣' })
        }
    }

    @Test
    fun `연결 거부는 서버 실행과 방화벽 포트를 짚어준다`() {
        val message = ConnectionErrorMessages.userMessage(ConnectionErrorKind.CONNECTION_REFUSED)

        assertTrue(message.contains("서버"))
        assertTrue(message.contains("방화벽"))
        // 사용자가 방화벽에서 열어야 하는 포트를 실제 상수에서 가져와야 한다
        assertTrue(message.contains(GestureConfig.DEFAULT_PORT.toString()))
    }

    @Test
    fun `타임아웃은 IP와 같은 Wi-Fi 여부를 확인하라고 안내한다`() {
        val message = ConnectionErrorMessages.userMessage(ConnectionErrorKind.TIMEOUT)

        assertTrue(message.contains("IP"))
        assertTrue(message.contains("Wi-Fi"))
    }

    @Test
    fun `주소 해석 실패는 IP 확인을 안내한다`() {
        assertTrue(
            ConnectionErrorMessages.userMessage(ConnectionErrorKind.UNKNOWN_HOST).contains("IP")
        )
    }

    @Test
    fun `네트워크 도달 불가는 Wi-Fi를 짚어준다`() {
        assertTrue(
            ConnectionErrorMessages.userMessage(ConnectionErrorKind.NETWORK_UNREACHABLE)
                .contains("Wi-Fi")
        )
    }

    @Test
    fun `핸드셰이크 실패는 Phone Pad 서버가 맞는지 묻는다`() {
        assertTrue(
            ConnectionErrorMessages.userMessage(ConnectionErrorKind.HANDSHAKE_FAILED)
                .contains("Phone Pad")
        )
    }

    @Test
    fun `단일 클라이언트 정책으로 밀려난 경우는 자동 복구되지 않음을 알린다`() {
        val message = ConnectionErrorMessages.userMessage(ConnectionErrorKind.SESSION_REPLACED)

        // 원인: 다른 기기가 들어왔다
        assertTrue(message.contains("기기"))
        // 조치: 자동으로 되돌아가지 않으니 직접 다시 연결해야 한다.
        // 이 종류만 자동 재연결을 하지 않으므로, 기다리면 복구된다는 오해를 남기면 안 된다.
        assertTrue(message.contains("자동"))
        assertTrue(message.contains("다시 연결"))
    }

    @Test
    fun `밀려난 경우의 문구는 일반 연결 끊김 문구와 다르다`() {
        // 조치가 정반대(하나는 "Wi-Fi/서버 확인 후 재연결", 하나는 "자동 복구 없음")라
        // 같은 문장을 쓰면 종류를 나눈 의미가 사라진다.
        assertNotEquals(
            ConnectionErrorMessages.userMessage(ConnectionErrorKind.CONNECTION_LOST),
            ConnectionErrorMessages.userMessage(ConnectionErrorKind.SESSION_REPLACED),
        )
    }

    @Test
    fun `원인을 아는 종류의 주 메시지에는 예외 원문이 섞이지 않는다`() {
        // 핵심 요구사항: 영어 원문이 주 메시지를 점령하면 안 된다.
        val knownKinds = ConnectionErrorKind.values().filter { it != ConnectionErrorKind.UNKNOWN }

        knownKinds.forEach { kind ->
            val message = ConnectionErrorMessages.userMessage(kind, raw)
            assertFalse("$kind 주 메시지에 원문이 섞였다", message.contains(raw))
        }
    }

    @Test
    fun `원인을 아는 종류에서는 원문이 보조 줄로 남는다`() {
        val detail = ConnectionErrorMessages.detail(ConnectionErrorKind.TIMEOUT, raw)

        assertEquals(raw, detail)
    }

    @Test
    fun `UNKNOWN은 원문을 접두사와 함께 주 메시지에 담는다`() {
        val message = ConnectionErrorMessages.userMessage(ConnectionErrorKind.UNKNOWN, raw)

        assertEquals("${ConnectionErrorMessages.UNKNOWN_PREFIX}$raw", message)
        // 같은 문자열을 두 번 보여주지 않는다
        assertNull(ConnectionErrorMessages.detail(ConnectionErrorKind.UNKNOWN, raw))
    }

    @Test
    fun `UNKNOWN이고 원문이 null이거나 비어 있으면 일반 문구로 떨어진다`() {
        assertEquals(
            ConnectionErrorMessages.GENERIC,
            ConnectionErrorMessages.userMessage(ConnectionErrorKind.UNKNOWN, null),
        )
        assertEquals(
            ConnectionErrorMessages.GENERIC,
            ConnectionErrorMessages.userMessage(ConnectionErrorKind.UNKNOWN, ""),
        )
        assertEquals(
            ConnectionErrorMessages.GENERIC,
            ConnectionErrorMessages.userMessage(ConnectionErrorKind.UNKNOWN, "   "),
        )
    }

    @Test
    fun `보조 줄은 원문이 없으면 생략된다`() {
        assertNull(ConnectionErrorMessages.detail(ConnectionErrorKind.TIMEOUT, null))
        assertNull(ConnectionErrorMessages.detail(ConnectionErrorKind.TIMEOUT, "   "))
        assertNotNull(ConnectionErrorMessages.detail(ConnectionErrorKind.TIMEOUT, "boom"))
    }

    @Test
    fun `내부 진단 문자열은 사용자 화면에 그대로 나가지 않는다`() {
        // 리포지토리가 실제로 쓰는 고정 리터럴들 (계약상 유지되는 값)
        val internals = mapOf(
            "Heartbeat timeout" to ConnectionErrorKind.HEARTBEAT_TIMEOUT,
            "Connection lost" to ConnectionErrorKind.CONNECTION_LOST,
            "Session handshake failed" to ConnectionErrorKind.HANDSHAKE_FAILED,
            "Reconnect failed: refused" to ConnectionErrorKind.RECONNECT_FAILED,
            "Session replaced by another device" to ConnectionErrorKind.SESSION_REPLACED,
        )

        internals.forEach { (internal, kind) ->
            val message = ConnectionErrorMessages.userMessage(kind, internal)
            assertFalse("$internal 이 주 메시지에 그대로 나갔다", message.contains(internal))
            // 그래도 원문은 보조 줄에 남아 있어야 한다
            assertEquals(internal, ConnectionErrorMessages.detail(kind, internal))
        }
    }
}
