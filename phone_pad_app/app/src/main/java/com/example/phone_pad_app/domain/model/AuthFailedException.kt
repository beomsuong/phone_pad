package com.example.phone_pad_app.domain.model

import java.io.IOException

/** [AuthFailedException]의 진단 메시지 접두사 — 이유가 붙으면 `"Auth failed: invalid_pin"` 형태가 된다. */
private const val AUTH_FAILED_MESSAGE = "Auth failed"

/** 서버가 보낸 `reason` 문자열을 진단 메시지에 실어 나를 때의 길이 상한. */
private const val MAX_REASON_LENGTH = 64

private fun authFailedMessage(reason: String?): String {
    val trimmed = reason?.trim()
    return if (trimmed.isNullOrEmpty()) {
        AUTH_FAILED_MESSAGE
    } else {
        "$AUTH_FAILED_MESSAGE: ${trimmed.take(MAX_REASON_LENGTH)}"
    }
}

/**
 * PIN 인증 거부 — 서버가 핸드셰이크 첫 응답으로 `AUTH_FAIL`을 보냈다는 뜻이다.
 *
 * **왜 별도 예외 타입인가:** 와이어 상으로는 "세션 한 줄이 오지 않았다"와 구분되지 않지만
 * (둘 다 SESSION을 못 받은 것이다), 사용자가 취할 조치가 완전히 다르다 — 핸드셰이크 실패는
 * "서버가 맞는지" 확인해야 하고, 이쪽은 "PC 화면의 PIN을 다시 입력"해야 한다. 그래서
 * [ConnectionErrorClassifier]가 **타입으로** 최우선 분류할 수 있도록 전용 타입을 둔다
 * (메시지 키워드 추측에 맡기지 않는다).
 *
 * **왜 domain 계층에 있는가:** 이 예외를 분류하는 [ConnectionErrorClassifier]가 domain에
 * 있어서다. data 계층(`TcpClient`)에 두면 domain이 data를 import하게 되어 의존 방향이 뒤집힌다.
 *
 * [IOException]을 상속하는 이유는 소켓 경로에서 던져지는 예외라 기존 `catch (e: Exception)`
 * 흐름(연결 실패 → `ConnectOutcome.Failure`)에 그대로 합류해야 하기 때문이다.
 *
 * @param reason 서버가 보낸 `reason` 필드(예: `"invalid_pin"`). 진단용이며 화면 문구는
 *   [ConnectionErrorKind.AUTH_FAILED]가 결정한다. 서버가 주는 값이므로 길이를 잘라 담는다.
 */
class AuthFailedException(val reason: String? = null) : IOException(authFailedMessage(reason))
