package com.example.phone_pad_app.presentation.trackpad

import com.example.phone_pad_app.presentation.util.GestureConfig
import kotlin.math.hypot

/**
 * 1손가락 더블탭 판정기.
 *
 * [MultiTouchGestureTracker]와 같은 설계 원칙을 따르는 순수 Kotlin 클래스다 — Compose의
 * `PointerInputScope`에도, 코루틴에도 의존하지 않으므로 JUnit 단위 테스트로 전부 검증할 수 있다.
 * "지연 후 전송"이라는 시간 축 처리는 이 클래스의 책임이 아니라 호출부(`TrackpadScreen`의
 * 지연 클릭 job)의 책임이며, 여기서는 **넘어온 두 탭이 하나의 더블탭인가**만 판정한다.
 *
 * 기억하는 상태는 **직전 탭 하나**뿐이다. 탭 히스토리를 쌓지 않는 이유:
 * - 트리플탭 이상은 이번 스펙의 범위가 아니다.
 * - 더블탭이 확정되면 즉시 상태를 비우므로, 연속 3번 탭하면 `A+B`가 더블클릭이 되고 `C`는
 *   완전히 새로운 첫 탭으로 시작한다 (`B+C`가 두 번째 더블클릭으로 겹쳐 잡히지 않는다).
 *
 * 스레드 안전하지 않다 — 하나의 포인터 입력 루프에서만 사용한다.
 */
class DoubleTapDetector {

    /** 아직 짝을 만나지 못한 직전 탭이 있는지. */
    private var hasPendingTap = false

    private var pendingX = 0f
    private var pendingY = 0f

    /** 직전 탭이 **끝난** 시각 (ms). 간격 판정의 기준점이다. */
    private var pendingTimestampMs = 0L

    /**
     * 1손가락 탭이 하나 끝났음을 알린다.
     *
     * @param x 탭 위치 X (폰 화면 좌표. [GestureEndDecision.x]를 그대로 넘기면 된다)
     * @param y 탭 위치 Y
     * @param timestampMs 탭이 끝난 시각 (ms)
     * @return 직전 탭과 묶여 **더블탭이 확정되면 true** (이 경우 내부 상태는 비워진다).
     *         아니면 이 탭을 새 "직전 탭"으로 기억하고 false.
     */
    fun onTap(x: Float, y: Float, timestampMs: Long): Boolean {
        if (hasPendingTap && isPairedWithPendingTap(x, y, timestampMs)) {
            // 확정 즉시 상태를 비운다 — 이 탭이 다음 탭의 짝으로 재사용되면
            // 세 번 탭했을 때 더블클릭이 두 번 나가버린다.
            reset()
            return true
        }

        hasPendingTap = true
        pendingX = x
        pendingY = y
        pendingTimestampMs = timestampMs
        return false
    }

    /**
     * 간격과 거리를 모두 만족하는지. 둘 다 **경계값 포함**(이내)으로 판정한다.
     *
     * 음수 간격(시각이 거꾸로 온 경우)은 짝으로 인정하지 않는다 — `System.currentTimeMillis()`는
     * 단조 증가가 보장되지 않아서(시각 동기화·시간대 변경) 이론상 뒤로 갈 수 있고, 그때
     * `elapsed <= INTERVAL`만 보면 아무리 오래된 탭과도 묶여버린다.
     */
    private fun isPairedWithPendingTap(x: Float, y: Float, timestampMs: Long): Boolean {
        val elapsed = timestampMs - pendingTimestampMs
        if (elapsed < 0L || elapsed > GestureConfig.DOUBLE_TAP_INTERVAL_MS) return false

        val distance = hypot(x - pendingX, y - pendingY)
        return distance <= GestureConfig.DOUBLE_TAP_DISTANCE_PX
    }

    /** 기억 중인 직전 탭을 버린다. */
    fun reset() {
        hasPendingTap = false
        pendingX = 0f
        pendingY = 0f
        pendingTimestampMs = 0L
    }
}
