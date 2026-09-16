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

    /** PC 서버 기본 포트 */
    const val DEFAULT_PORT = 9000
}
