package com.example.phone_pad_app.data.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SessionHandshakeTest {

    @Test
    fun `확정 스펙 형식의 핸드셰이크에서 세션 토큰을 파싱한다`() {
        val line = """{"type":"SESSION","session":"0123456789abcdef0123456789abcdef"}"""

        assertEquals("0123456789abcdef0123456789abcdef", SessionHandshake.parseSession(line))
    }

    @Test
    fun `공백과 필드 순서가 달라도 파싱한다`() {
        val line = """{ "session" : "abc123" , "type" : "SESSION" }"""

        assertEquals("abc123", SessionHandshake.parseSession(line))
    }

    @Test
    fun `알 수 없는 추가 필드가 있어도 파싱한다`() {
        val line = """{"type":"SESSION","session":"deadbeef","proto":2}"""

        assertEquals("deadbeef", SessionHandshake.parseSession(line))
    }

    @Test
    fun `type이 SESSION이 아니면 null을 반환한다`() {
        val line = """{"type":"HEARTBEAT_ACK","session":"abc123"}"""

        assertNull(SessionHandshake.parseSession(line))
    }

    @Test
    fun `session 필드가 없으면 null을 반환한다`() {
        assertNull(SessionHandshake.parseSession("""{"type":"SESSION"}"""))
    }

    @Test
    fun `session 값이 빈 문자열이면 null을 반환한다`() {
        assertNull(SessionHandshake.parseSession("""{"type":"SESSION","session":""}"""))
    }

    @Test
    fun `null 또는 빈 줄이면 null을 반환한다`() {
        assertNull(SessionHandshake.parseSession(null))
        assertNull(SessionHandshake.parseSession(""))
        assertNull(SessionHandshake.parseSession("   "))
    }

    @Test
    fun `JSON이 아닌 쓰레기 값이면 null을 반환한다`() {
        assertNull(SessionHandshake.parseSession("SESSION abc123"))
    }
}
