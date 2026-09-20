package com.example.phone_pad_app.domain.model

/**
 * 연결 실패/유실의 **원인 종류**.
 *
 * [ConnectionState.Error]의 `message`는 내부 진단용 문자열(영문 원문 또는 고정 리터럴)이고,
 * 이 열거형은 그 원인을 **사용자에게 보여줄 문구로 바꾸기 위한 단서**다. 두 가지를 분리한 이유:
 * - `message`는 기존 계약(예: `"Heartbeat timeout"`, `"Connection lost"`)을 그대로 유지해야 한다.
 *   재연결 판정/테스트가 그 문자열을 기준으로 삼고 있고, 로그에는 원문이 남는 편이 낫다.
 * - 사용자에게는 예외 원문 대신 "무엇을 확인해야 하는지"를 한국어로 알려줘야 한다. 그 변환은
 *   표시 계층([com.example.phone_pad_app.presentation.util.ConnectionErrorMessages])이 담당하며,
 *   원문을 문자열로 다시 파싱하지 않도록 종류를 여기 실어 나른다.
 *
 * 분류 자체는 [ConnectionErrorClassifier]가 [Throwable]에서 수행한다 — 예외 객체가 살아 있는
 * data 계층에서만 가능한 일이라, UI까지 예외를 끌고 가는 대신 종류만 넘긴다.
 */
enum class ConnectionErrorKind {
    /** TCP 연결 거부 — 서버 미실행 또는 방화벽 차단. */
    CONNECTION_REFUSED,

    /** 연결 시도가 [com.example.phone_pad_app.presentation.util.GestureConfig.CONNECT_TIMEOUT_MS] 안에 끝나지 않음. */
    TIMEOUT,

    /** 호스트 이름/주소를 해석할 수 없음. */
    UNKNOWN_HOST,

    /** 네트워크 자체에 닿지 않음 (Wi-Fi 꺼짐, 다른 서브넷 등). */
    NETWORK_UNREACHABLE,

    /** TCP는 붙었지만 세션 핸드셰이크 한 줄이 오지 않음 — Phone Pad 서버가 아니거나 버전 불일치. */
    HANDSHAKE_FAILED,

    /** 연결 유지 중 heartbeat 미응답이 한계치에 도달 (`"Heartbeat timeout"`). */
    HEARTBEAT_TIMEOUT,

    /** 연결 유지 중 소켓이 끊김 — EOF, 전송 실패 등 (`"Connection lost"`). */
    CONNECTION_LOST,

    /** 자동 재연결 시도를 모두 소진 (`"Reconnect failed: ..."`). */
    RECONNECT_FAILED,

    /** 위 어디에도 해당하지 않음 — 표시 계층이 원문을 함께 보여주는 폴백. */
    UNKNOWN,
}
