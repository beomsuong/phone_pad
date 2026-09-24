package com.example.phone_pad_app.data.network

/**
 * 서버가 **이 연결을 강제로 닫기 직전에 딱 한 번** 보내는 단일 클라이언트 정책 알림
 * (AGENTS.md 섹션 4 / 확정 스펙).
 *
 * ```
 * 서버 -> 클라이언트   {"type":"SESSION_REPLACED"}
 * ```
 *
 * 필드가 없고 `session`도 없다 — 이 연결 자체가 곧 끊기므로 실을 정보가 없다.
 *
 * **왜 [com.example.phone_pad_app.domain.model.TrackpadEvent] sealed class가 아닌가:**
 * HEARTBEAT/HEARTBEAT_ACK와 같은 범주의 **연결 유지/전송 계층 전용 메시지**라서다
 * (AGENTS.md 섹션 9 예외 규칙). 제스처가 아니므로 `SendEventUseCase`를 경유해 presentation이
 * 다룰 물건이 아니고, 방향도 서버 -> 클라이언트 단방향이다.
 *
 * 소켓과 분리된 순수 함수로 두어 JVM 단위 테스트에서 그대로 검증한다 —
 * [SessionHandshake]/[AuthHandshake]와 같은 방식(전체 JSON 파서를 끌어오지 않는다).
 */
object SessionReplacedNotice {

    /** 확정 스펙의 `type` 값. 대소문자·부분 일치는 허용하지 않는다. */
    const val SESSION_REPLACED_TYPE = "SESSION_REPLACED"

    private val TYPE_REGEX = Regex("\"type\"\\s*:\\s*\"([^\"]*)\"")

    /**
     * 읽은 한 줄이 단일 클라이언트 정책 알림인가?
     *
     * 판별을 **정확 일치**로 좁히는 것이 요점이다. 이 줄 하나가 "자동 재연결을 하지 않는다"는
     * 예외 경로를 여는 스위치라서, 접두사/부분 일치로 느슨하게 잡으면 엉뚱한 하향 줄이
     * 재연결을 통째로 막아버린다. 형식이 어긋나거나 다른 type(HEARTBEAT_ACK 등)이면 false이고,
     * 호출자는 기존대로 "아무 줄이나 수신"으로 처리한다.
     *
     * @param line 서버가 보낸 한 줄 (개행 제외). 연결이 끊겼으면 null.
     */
    fun isSessionReplaced(line: String?): Boolean {
        if (line.isNullOrBlank()) return false
        return TYPE_REGEX.find(line)?.groupValues?.getOrNull(1) == SESSION_REPLACED_TYPE
    }
}
