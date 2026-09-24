package com.example.phone_pad_app.data.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 단일 클라이언트 정책 알림의 리터럴 판별 (순수 함수).
 *
 * 이 한 줄이 "자동 재연결을 하지 않는다"는 예외 경로의 유일한 스위치라, **거짓 양성**이
 * 훨씬 위험하다(엉뚱한 하향 줄이 재연결을 통째로 막는다). 그래서 정상 형식을 잡는 것만큼
 * HEARTBEAT_ACK·유사 type·부분 일치를 **거절**하는 쪽을 촘촘히 고정한다.
 */
class SessionReplacedNoticeTest {

    @Test
    fun `확정 스펙 형식의 알림을 인식한다`() {
        assertTrue(SessionReplacedNotice.isSessionReplaced("""{"type":"SESSION_REPLACED"}"""))
    }

    @Test
    fun `와이어 리터럴은 확정 스펙 문자열과 정확히 일치한다`() {
        // 서버(pc_server)가 보내는 값과의 계약. 이 상수가 바뀌면 경계면이 조용히 깨진다.
        assertEquals("SESSION_REPLACED", SessionReplacedNotice.SESSION_REPLACED_TYPE)
    }

    @Test
    fun `공백이 섞여도 인식한다`() {
        assertTrue(SessionReplacedNotice.isSessionReplaced("""{ "type" : "SESSION_REPLACED" }"""))
    }

    @Test
    fun `알 수 없는 추가 필드가 있어도 인식한다`() {
        // 스펙상 필드는 없지만, 서버가 나중에 진단용 필드를 덧붙여도 판별은 유지되어야 한다.
        assertTrue(
            SessionReplacedNotice.isSessionReplaced("""{"type":"SESSION_REPLACED","by":"other"}""")
        )
    }

    @Test
    fun `HEARTBEAT_ACK는 알림이 아니다`() {
        // 가장 흔한 하향 줄. 이것을 잘못 잡으면 정상 연결이 매번 끊긴다.
        assertFalse(SessionReplacedNotice.isSessionReplaced("""{"type":"HEARTBEAT_ACK"}"""))
    }

    @Test
    fun `SESSION 핸드셰이크 줄은 알림이 아니다`() {
        assertFalse(
            SessionReplacedNotice.isSessionReplaced(
                """{"type":"SESSION","session":"0123456789abcdef0123456789abcdef"}"""
            )
        )
    }

    @Test
    fun `type 값이 정확히 일치하지 않으면 알림이 아니다`() {
        listOf(
            """{"type":"SESSION_REPLACED_LATER"}""",
            """{"type":"XSESSION_REPLACED"}""",
            """{"type":"session_replaced"}""",
            """{"type":"SESSION REPLACED"}""",
            """{"type":""}""",
        ).forEach {
            assertFalse("$it 를 알림으로 오인했다", SessionReplacedNotice.isSessionReplaced(it))
        }
    }

    @Test
    fun `다른 필드에 문자열이 들어 있어도 알림이 아니다`() {
        // type이 아닌 곳의 값으로 스위치가 켜지면 안 된다.
        assertFalse(
            SessionReplacedNotice.isSessionReplaced("""{"type":"HEARTBEAT_ACK","note":"SESSION_REPLACED"}""")
        )
    }

    @Test
    fun `null 또는 빈 줄은 알림이 아니다`() {
        assertFalse(SessionReplacedNotice.isSessionReplaced(null))
        assertFalse(SessionReplacedNotice.isSessionReplaced(""))
        assertFalse(SessionReplacedNotice.isSessionReplaced("   "))
    }

    @Test
    fun `JSON이 아닌 쓰레기 값은 알림이 아니다`() {
        assertFalse(SessionReplacedNotice.isSessionReplaced("SESSION_REPLACED"))
        assertFalse(SessionReplacedNotice.isSessionReplaced("type=SESSION_REPLACED"))
    }
}
