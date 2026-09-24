package com.example.phone_pad_app.presentation.util

import com.example.phone_pad_app.domain.model.ConnectionErrorKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PIN 인증 실패 문구의 **계약** (문장 전문은 고정하지 않는다 — AGENTS.md 섹션 9).
 *
 * 고정하는 것: PIN을 짚어준다 / 어디를 봐야 하는지 알려준다 / 영문 원문이 주 메시지를
 * 점령하지 않는다 / 핸드셰이크 실패 문구와 섞이지 않는다.
 */
class AuthFailedMessageTest {

    private val kind = ConnectionErrorKind.AUTH_FAILED
    private val raw = "Auth failed: invalid_pin"

    @Test
    fun `PIN 오류 문구는 PIN과 확인할 곳을 짚어준다`() {
        val message = ConnectionErrorMessages.userMessage(kind, raw)

        assertTrue(message.contains("PIN"))
        assertTrue("어디를 봐야 하는지 알려주지 않는다", message.contains("PC"))
    }

    @Test
    fun `내부 진단 문자열은 주 메시지에 섞이지 않고 보조 줄에 남는다`() {
        val message = ConnectionErrorMessages.userMessage(kind, raw)

        assertFalse(message.contains(raw))
        assertEquals(raw, ConnectionErrorMessages.detail(kind, raw))
    }

    @Test
    fun `핸드셰이크 실패와 다른 문구를 준다`() {
        // 조치가 정반대다: 한쪽은 "서버가 맞는지", 이쪽은 "PIN을 다시 입력".
        assertFalse(
            ConnectionErrorMessages.userMessage(kind) ==
                ConnectionErrorMessages.userMessage(ConnectionErrorKind.HANDSHAKE_FAILED)
        )
    }

    @Test
    fun `원문이 없어도 문구가 나온다`() {
        assertTrue(ConnectionErrorMessages.userMessage(kind, null).isNotBlank())
        assertTrue(ConnectionErrorMessages.userMessage(kind, "").isNotBlank())
    }
}
