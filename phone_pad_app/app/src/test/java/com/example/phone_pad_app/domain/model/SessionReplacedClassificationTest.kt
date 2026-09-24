package com.example.phone_pad_app.domain.model

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException

/**
 * [SessionReplacedException] → [ConnectionErrorKind.SESSION_REPLACED] 분류 (순수 함수).
 *
 * [AuthFailedClassificationTest]와 같은 이유로 별도 파일 — "타입이 메시지 추측보다
 * 우선한다"는 규칙을 이 예외 타입에 대해서도 고정한다(protocol-qa W-1 수정).
 */
class SessionReplacedClassificationTest {

    @Test
    fun `SessionReplacedException은 SESSION_REPLACED로 분류된다`() {
        assertEquals(
            ConnectionErrorKind.SESSION_REPLACED,
            ConnectionErrorClassifier.classify(SessionReplacedException()),
        )
    }

    @Test
    fun `원인 체인 안의 SessionReplacedException도 찾아낸다`() {
        val wrapped = IOException("connect failed", SessionReplacedException())

        assertEquals(
            ConnectionErrorKind.SESSION_REPLACED,
            ConnectionErrorClassifier.classify(wrapped),
        )
    }

    @Test
    fun `진단 메시지는 PIN처럼 값을 담지 않는 고정 리터럴이다`() {
        assertEquals("Session replaced by another device", SessionReplacedException().message)
    }
}
