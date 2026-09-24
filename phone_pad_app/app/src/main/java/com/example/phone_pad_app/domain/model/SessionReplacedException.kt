package com.example.phone_pad_app.domain.model

import java.io.IOException

/** 진단 메시지. [AuthFailedException]의 [AUTH_FAILED_MESSAGE]와 같은 자리의 상수. */
private const val SESSION_REPLACED_MESSAGE = "Session replaced by another device"

/**
 * 핸드셰이크 도중 `SESSION` 대신 `SESSION_REPLACED`를 받았다는 뜻이다.
 *
 * **발생 조건 (protocol-qa W-1, 매우 드묾):** 서버의 단일 클라이언트 정책은 "밀어내기 →
 * SESSION 발급" 순서로 동작하지만 그 사이에 창이 있다 — 우리(B)가 기존 클라이언트(A)를
 * 밀어내고 활성 슬롯을 차지한 직후, SESSION을 받기도 전에 **세 번째 기기(C)**가 인증에
 * 성공하면 서버는 B를 곧바로 다시 밀어낸다. 이 경우 B가 핸드셰이크에서 받는 첫 줄은
 * `SESSION`이 아니라 `SESSION_REPLACED`다.
 *
 * **왜 별도 예외 타입인가:** 이걸 구분하지 않으면 `SessionHandshake.parseSession()`이
 * null을 돌려줘 [ConnectionErrorKind.HANDSHAKE_FAILED]("Phone Pad 서버가 아니거나
 * 버전이 다릅니다")로 분류된다 — 서버는 정상인데 완전히 엉뚱한 안내가 뜬다. 실제로는
 * "다른 기기가 방금 접속했다"는 것이므로 [ConnectionErrorKind.SESSION_REPLACED]와 같은
 * 문구([com.example.phone_pad_app.presentation.util.ConnectionErrorMessages])를 보여줘야
 * 사용자가 다시 시도해야 하는 이유를 이해한다.
 *
 * [AuthFailedException]과 마찬가지로 [IOException]을 상속해 기존
 * `catch (e: Exception)` → [ConnectionErrorClassifier] 흐름에 그대로 합류한다.
 *
 * **재연결 루프에서의 취급:** `AUTH_FAILED`와 달리 이 kind는 재연결 루프를 즉시 중단시키지
 * 않는다 — PIN 불일치처럼 "기다려도 해결 안 되는" 실패가 아니라 세 기기가 동시에 접속을
 * 시도한 순간의 우연한 경합이라, 다음 백오프에서는 이 경합이 다시 일어날 가능성이 낮다.
 */
class SessionReplacedException : IOException(SESSION_REPLACED_MESSAGE)
