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

    /**
     * 서버가 PIN 인증을 거부함 (`AUTH_FAIL`) — 입력한 PIN이 PC 화면의 값과 다르다.
     *
     * [HANDSHAKE_FAILED]와 분리하는 이유는 조치가 정반대라서다. 핸드셰이크 실패는 "저쪽이
     * Phone Pad 서버가 맞는가"를 의심해야 하지만, 이쪽은 서버가 분명히 맞고(형식이 맞는 거부
     * 응답을 보냈으므로) 사용자가 PIN만 다시 입력하면 된다. 분류는 [AuthFailedException]
     * 타입으로 이루어진다.
     */
    AUTH_FAILED,

    /** 연결 유지 중 heartbeat 미응답이 한계치에 도달 (`"Heartbeat timeout"`). */
    HEARTBEAT_TIMEOUT,

    /** 연결 유지 중 소켓이 끊김 — EOF, 전송 실패 등 (`"Connection lost"`). */
    CONNECTION_LOST,

    /** 자동 재연결 시도를 모두 소진 (`"Reconnect failed: ..."`). */
    RECONNECT_FAILED,

    /**
     * 다른 기기가 같은 PC에 접속해 이 연결이 밀려남 — 서버의 단일 클라이언트 정책
     * (서버가 보낸 `{"type":"SESSION_REPLACED"}` 한 줄, AGENTS.md 섹션 4).
     *
     * [CONNECTION_LOST]와 분리하는 이유는 **동작이 다르기 때문**이다. 다른 유실은 전부
     * 자동 재연결을 시작하지만 이 종류만은 재연결하지 않고 곧바로 `Error`로 간다 — 재연결하면
     * 방금 자기를 밀어낸 상대를 다시 밀어내게 되어 두 기기가 서로 뺏고 뺏는 무한 핑퐁이 된다.
     * 사용자에게 안내할 내용도 "Wi-Fi/서버를 확인하라"가 아니라 "자동으로 되돌아가지 않으니
     * 직접 다시 연결하라"로 정반대다.
     *
     * [Throwable]에서 분류되지 않는다 — [HEARTBEAT_TIMEOUT]/[RECONNECT_FAILED]와 마찬가지로
     * 코드가 직접 지정하므로 [ConnectionErrorClassifier]는 이 값을 만들지 않는다.
     */
    SESSION_REPLACED,

    /** 위 어디에도 해당하지 않음 — 표시 계층이 원문을 함께 보여주는 폴백. */
    UNKNOWN,
}
