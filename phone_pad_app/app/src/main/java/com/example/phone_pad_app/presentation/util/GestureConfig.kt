package com.example.phone_pad_app.presentation.util

object GestureConfig {
    /** 이동 감도 배율 */
    const val MOVE_SENSITIVITY = 1.5f

    /** 탭으로 인정할 최대 이동 거리 (px) */
    const val TAP_MAX_DISTANCE_PX = 20f

    /** 탭으로 인정할 최대 지속 시간 (ms) */
    const val TAP_MAX_DURATION_MS = 200L

    /** 커서 이동 이벤트를 발생시킬 최소 이동 거리 (px) — 탭 중 미세 떨림 억제 */
    const val MOVE_MIN_DISTANCE_PX = 5f

    /** PC 서버 기본 포트 (TCP — CLICK/SCROLL/DRAG/HEARTBEAT 및 세션 핸드셰이크) */
    const val DEFAULT_PORT = 9000

    /** PC 서버 UDP 포트 (MOVE 전용) */
    const val UDP_PORT = 9001

    /** TCP 연결 직후 세션 핸드셰이크 한 줄을 기다리는 최대 시간 (ms) */
    const val SESSION_HANDSHAKE_TIMEOUT_MS = 3000

    /**
     * TCP heartbeat 전송 주기 (ms).
     * 연결 유지 중 이 간격으로 `{"type":"HEARTBEAT"}`를 보내고,
     * 동일한 값을 소켓 `soTimeout`으로 사용해 수신 루프의 1회 대기 시간으로 삼는다.
     */
    const val HEARTBEAT_INTERVAL_MS = 5000L

    /** 연속 미응답(읽기 타임아웃) 허용 횟수 — 이 횟수에 도달하면 연결이 끊긴 것으로 판정한다. */
    const val HEARTBEAT_MISS_LIMIT = 3
}
