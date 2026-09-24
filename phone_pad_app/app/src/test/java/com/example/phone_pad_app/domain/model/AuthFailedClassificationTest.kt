package com.example.phone_pad_app.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * [AuthFailedException] → [ConnectionErrorKind.AUTH_FAILED] 분류 (순수 함수).
 *
 * 왜 별도 파일인가: 기존 [ConnectionErrorClassifierTest]는 소켓 예외 계열만 다루고, 이쪽은
 * "타입이 메시지 추측보다 우선한다"는 새 규칙을 고정한다.
 */
class AuthFailedClassificationTest {

    @Test
    fun `AuthFailedException은 AUTH_FAILED로 분류된다`() {
        assertEquals(
            ConnectionErrorKind.AUTH_FAILED,
            ConnectionErrorClassifier.classify(AuthFailedException("invalid_pin")),
        )
    }

    @Test
    fun `reason이 없어도 AUTH_FAILED다`() {
        assertEquals(
            ConnectionErrorKind.AUTH_FAILED,
            ConnectionErrorClassifier.classify(AuthFailedException()),
        )
    }

    @Test
    fun `타입 판정이 메시지 키워드보다 우선한다`() {
        // 서버가 보낸 reason에 "refused"/"timeout" 같은 단어가 섞여도 원인은 PIN 불일치다.
        assertEquals(
            ConnectionErrorKind.AUTH_FAILED,
            ConnectionErrorClassifier.classify(AuthFailedException("connection refused")),
        )
        assertEquals(
            ConnectionErrorKind.AUTH_FAILED,
            ConnectionErrorClassifier.classify(AuthFailedException("timed out")),
        )
    }

    @Test
    fun `원인 체인 안의 AuthFailedException도 찾아낸다`() {
        val wrapped = IOException("connect failed", AuthFailedException("invalid_pin"))

        assertEquals(ConnectionErrorKind.AUTH_FAILED, ConnectionErrorClassifier.classify(wrapped))
    }

    @Test
    fun `핸드셰이크 실패와 섞이지 않는다`() {
        // "서버가 아예 응답하지 않음"은 여전히 HANDSHAKE_FAILED 경로다(리포지토리가 정한다).
        assertFalse(
            ConnectionErrorClassifier.classify(IOException("no session")) ==
                ConnectionErrorKind.AUTH_FAILED
        )
    }

    @Test
    fun `진단 메시지는 영문 고정 리터럴이고 PIN 값을 담지 않는다`() {
        // ConnectionState.Error.message는 진단용 계약이다 (AGENTS.md 섹션 9). 또한 사용자가
        // 입력한 PIN은 예외 메시지로도 새어나가지 않는다 — 예외는 reason만 들고 온다.
        val message = AuthFailedException("invalid_pin").message!!

        assertTrue(message.startsWith("Auth failed"))
        assertFalse(message.contains("483920"))
    }

    @Test
    fun `서버가 보낸 긴 reason은 잘려서 담긴다`() {
        // reason은 서버가 주는 값이므로 진단 문자열을 무한히 부풀리지 못하게 한다.
        val long = "x".repeat(500)

        val message = AuthFailedException(long).message!!

        assertTrue("길이 상한이 없다: ${message.length}", message.length < 200)
    }
}
