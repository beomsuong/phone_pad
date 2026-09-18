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

    /**
     * 단일 포인터(1손가락) 구간으로 판정할 동시 포인터 개수.
     * 이 개수인 구간에서만 MOVE를 방출하며, 탭으로 끝나면 좌클릭(CLICK button="left")이다.
     */
    const val SINGLE_POINTER_COUNT = 1

    /**
     * 2손가락 구간으로 판정할 동시 포인터 개수.
     * 이 개수인 구간이 탭으로 끝나면 우클릭(CLICK button="right")이다 (AGENTS.md 섹션 5).
     */
    const val DOUBLE_POINTER_COUNT = 2

    /**
     * 멀티터치 해제 유예 시간 (ms).
     *
     * 2손가락을 "동시에" 떼는 것은 물리적으로 불가능해서, 실제 기기에서는 거의 항상
     * `2손가락 → 1손가락 → 0` 순으로 이벤트가 들어온다. 마지막 구간만 보면 이 짧은
     * 1손가락 꼬리 때문에 2손가락 탭이 좌클릭으로 뒤집힌다. 마지막 구간이 **포인터 개수 감소로**
     * 시작됐고 이 시간 안에 제스처가 끝났다면, 꼬리를 무시하고 직전(더 많은 손가락) 구간으로 판정한다.
     *
     * 반드시 [TAP_MAX_DURATION_MS]보다 충분히 작아야 한다 — 그렇지 않으면 "손가락 하나를 떼고
     * 남은 손가락으로 탭"하는 정상 동작까지 삼켜버린다.
     */
    const val MULTI_TOUCH_RELEASE_GRACE_MS = 50L

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
