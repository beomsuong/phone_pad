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
     * 휠 스크롤 1스텝(노치)에 해당하는 centroid 이동 거리 (px).
     *
     * 2손가락 드래그의 픽셀 이동을 `{"type":"SCROLL","dx":..,"dy":..}`의 정수 스텝으로 나눌 때 쓰는
     * 나눗수다. 값이 작을수록 같은 거리에서 더 많은 스텝이 나가 스크롤이 빨라진다.
     *
     * 기본값 40px의 근거:
     * - 반드시 [TAP_MAX_DISTANCE_PX](20px)보다 커야 한다. 스크롤은 `isDrag`(= 탭 한계 초과) 이후에만
     *   시작되는데, 스텝 단위가 탭 한계보다 작으면 스크롤이 걸리는 바로 그 순간 이미 1스텝 이상이
     *   쌓여 있어서 "손을 대자마자 툭 튀는" 느낌이 된다. 2배로 두면 스크롤 진입이 0스텝에서 시작한다.
     * - Windows에서 휠 1노치는 보통 3줄이므로, 실사용에서 편한 400px 정도의 2손가락 스와이프가
     *   10스텝 ≈ 30줄 ≈ 텍스트 한 화면 분량이 된다.
     * 실기기 체감 튜닝은 별도 과제(Phase 3 감도 설정 UI)로 남긴다.
     */
    const val SCROLL_SENSITIVITY_PX_PER_STEP = 40f

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

    /**
     * 두 탭을 하나의 더블탭으로 묶을 최대 간격 (ms) — 첫 탭 종료 ~ 둘째 탭 종료.
     *
     * 이 값은 동시에 **모든 1손가락 탭(좌클릭)이 겪는 지연 시간**이기도 하다. 탭이 끝난 즉시
     * CLICK을 보내버리면 뒤이어 오는 두 번째 탭과 병합할 기회가 사라지므로, 이 시간만큼
     * 기다렸다가 두 번째 탭이 없을 때 비로소 CLICK을 보낸다 (`TrackpadScreen`의 지연 클릭 job).
     * 더블클릭을 지원하는 구조에서 피할 수 없는 트레이드오프이며, 값을 키우면 더블탭 인식은
     * 관대해지지만 단일 클릭 체감 지연이 그만큼 늘어난다. 실기기 미검증 — 조정 가능.
     */
    const val DOUBLE_TAP_INTERVAL_MS = 300L

    /**
     * 두 탭의 중심 좌표 사이 최대 허용 거리 (px).
     *
     * [TAP_MAX_DISTANCE_PX](20px)의 2배 — 사람이 같은 자리를 두 번 탭해도 몇 px씩 어긋나므로,
     * "탭 하나로 인정되는 이동 거리"보다 넉넉하게 잡아야 두 번째 탭이 더블탭으로 묶인다.
     * 이 좌표 판정은 폰 화면 좌표계에서만 이루어지며 PC 커서 위치와는 무관하다 —
     * 서버는 커서를 전혀 움직이지 않고 DOUBLE_CLICK 하나만 처리한다 (AGENTS.md 섹션 4).
     */
    const val DOUBLE_TAP_DISTANCE_PX = 40f

    /**
     * 1손가락을 제자리에 유지해야 드래그 홀드로 승격되는 시간 (ms) — `DRAG_START` 전송 시점.
     *
     * [TAP_MAX_DURATION_MS]와 **정확히 같은 값**이어야 하므로 숫자를 따로 적지 않고 참조한다:
     * "탭으로 인정되지 않게 되는 바로 그 시점"이 "드래그 홀드가 되는 시점"과 일치해야
     * 사각지대(탭도 드래그 홀드도 아닌 구간)가 생기지 않는다. 동시에 이 등식 덕분에
     * [MultiTouchGestureTracker][com.example.phone_pad_app.presentation.trackpad.MultiTouchGestureTracker]의
     * 탭 판정(`elapsed < TAP_MAX_DURATION_MS`)과 승격 판정(`elapsed >= DRAG_HOLD_THRESHOLD_MS`)이
     * 구조적으로 상호 배타가 된다.
     *
     * 값을 바꾸려면 탭 판정 시간과 함께 움직여야 한다 — 한쪽만 바꾸면 위 성질이 깨진다.
     */
    const val DRAG_HOLD_THRESHOLD_MS: Long = TAP_MAX_DURATION_MS

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
