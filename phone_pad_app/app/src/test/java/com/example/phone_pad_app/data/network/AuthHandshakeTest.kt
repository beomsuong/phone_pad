package com.example.phone_pad_app.data.network

import com.example.phone_pad_app.presentation.util.GestureConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AUTH 줄 생성 / AUTH_FAIL 판별 (순수 함수 — 소켓 없음).
 *
 * 여기서 고정하는 것은 **서버와의 와이어 계약**이다: 키 순서 `type` → `pin`, 공백 없음,
 * 한 줄에 개행이 섞이지 않음. 서버(pc_server의 `parse_auth_message`)가 이 형식만 통과시킨다.
 */
class AuthHandshakeTest {

    @Test
    fun `AUTH 줄은 확정된 와이어 리터럴과 정확히 일치한다`() {
        assertEquals(
            """{"type":"AUTH","pin":"483920"}""",
            AuthHandshake.buildAuthLine("483920"),
        )
    }

    @Test
    fun `AUTH 줄에는 개행이 붙지 않는다`() {
        // 개행은 전송 계층(PrintWriter.println)이 붙인다 — 여기서 붙으면 빈 줄이 하나 더 나간다.
        val line = AuthHandshake.buildAuthLine("000000")

        assertFalse(line.contains("\n"))
        assertFalse(line.contains("\r"))
    }

    @Test
    fun `PIN이 비어 있어도 형식은 같다`() {
        // 인증이 꺼진 서버(--no-auth)는 pin 값을 보지 않는다. 형식이 갈라지면 안 된다(확정 스펙).
        assertEquals("""{"type":"AUTH","pin":""}""", AuthHandshake.buildAuthLine(""))
    }

    @Test
    fun `PIN은 AUTH_PIN_MAX_LENGTH로 잘린다`() {
        val long = "9".repeat(GestureConfig.AUTH_PIN_MAX_LENGTH + 50)

        val line = AuthHandshake.buildAuthLine(long)

        assertEquals(
            """{"type":"AUTH","pin":"${"9".repeat(GestureConfig.AUTH_PIN_MAX_LENGTH)}"}""",
            line,
        )
    }

    @Test
    fun `큰따옴표와 백슬래시는 이스케이프되어 JSON이 깨지지 않는다`() {
        // 깨진 JSON을 보내면 서버가 아무 응답 없이 연결을 닫아(확정 스펙) 사용자에게는
        // 원인 없는 실패로 보인다. 최소한 유효한 JSON을 보내고 "불일치" 답을 받는 편이 낫다.
        val line = AuthHandshake.buildAuthLine("""a"b\c""")

        assertEquals("""{"type":"AUTH","pin":"a\"b\\c"}""", line)
    }

    @Test
    fun `제어문자는 제거되어 한 줄이 쪼개지지 않는다`() {
        val line = AuthHandshake.buildAuthLine("12\n34\r56\t78")

        // 개행이 남으면 서버가 AUTH 다음 조각을 별개의 메시지로 읽는다.
        assertEquals("""{"type":"AUTH","pin":"12345678"}""", line)
        assertFalse(line.contains("\n"))
        assertFalse(line.contains("\r"))
        assertFalse(line.contains("\t"))
    }

    @Test
    fun `AUTH_FAIL 줄을 알아본다`() {
        assertTrue(AuthHandshake.isAuthFail("""{"type":"AUTH_FAIL","reason":"invalid_pin"}"""))
        // 서버의 json.dumps 기본형(콜론 뒤 공백)도 같은 줄이다.
        assertTrue(AuthHandshake.isAuthFail("""{"type": "AUTH_FAIL", "reason": "invalid_pin"}"""))
    }

    @Test
    fun `AUTH_FAIL이 아닌 줄은 거부로 보지 않는다`() {
        // SESSION을 거부로 오인하면 정상 연결이 전부 PIN 오류가 된다.
        assertFalse(AuthHandshake.isAuthFail("""{"type":"SESSION","session":"abc"}"""))
        assertFalse(AuthHandshake.isAuthFail("""{"type":"auth_fail"}"""))
        assertFalse(AuthHandshake.isAuthFail("""{"type":"AUTH_FAILED"}"""))
        assertFalse(AuthHandshake.isAuthFail("not json"))
        assertFalse(AuthHandshake.isAuthFail(""))
        assertFalse(AuthHandshake.isAuthFail("   "))
        assertFalse(AuthHandshake.isAuthFail(null))
    }

    @Test
    fun `reason은 진단용으로 뽑아낼 수 있고 없으면 null이다`() {
        assertEquals(
            "invalid_pin",
            AuthHandshake.parseFailReason("""{"type":"AUTH_FAIL","reason":"invalid_pin"}"""),
        )
        assertNull(AuthHandshake.parseFailReason("""{"type":"AUTH_FAIL"}"""))
        assertNull(AuthHandshake.parseFailReason("""{"type":"AUTH_FAIL","reason":""}"""))
        assertNull(AuthHandshake.parseFailReason(null))
    }

    @Test
    fun `AUTH와 AUTH_FAIL 타입 문자열은 서버 스펙과 같다`() {
        assertEquals("AUTH", AuthHandshake.AUTH_TYPE)
        assertEquals("AUTH_FAIL", AuthHandshake.AUTH_FAIL_TYPE)
    }
}
