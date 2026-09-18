package com.example.phone_pad_app.presentation.trackpad

import com.example.phone_pad_app.presentation.util.GestureConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 더블탭 병합 판정 단위 테스트.
 *
 * [MultiTouchGestureTrackerTest]와 같은 원칙으로, 임계값은 하드코딩하지 않고 전부
 * [GestureConfig]에서 파생시킨다 — 상수를 조정해도 "이내/초과"의 의도가 그대로 검증된다.
 */
class DoubleTapDetectorTest {

    /** 간격 이내로 확실히 인정되는 시간차 */
    private val withinInterval = GestureConfig.DOUBLE_TAP_INTERVAL_MS / 2

    /** 간격을 확실히 넘는 시간차 */
    private val beyondInterval = GestureConfig.DOUBLE_TAP_INTERVAL_MS + 1

    /** 거리 이내로 확실히 인정되는 좌표 차 */
    private val withinDistance = GestureConfig.DOUBLE_TAP_DISTANCE_PX / 2f

    /** 거리를 확실히 넘는 좌표 차 */
    private val beyondDistance = GestureConfig.DOUBLE_TAP_DISTANCE_PX * 2f

    private val detector = DoubleTapDetector()

    // ---------------------------------------------------------------- 기본 동작

    @Test
    fun `첫 탭은 언제나 더블탭이 아니다`() {
        assertFalse("직전 탭이 없으므로 묶일 상대가 없다", detector.onTap(100f, 100f, 0L))
    }

    @Test
    fun `간격과 거리가 모두 이내면 두 번째 탭에서 더블탭이 확정된다`() {
        assertFalse(detector.onTap(100f, 100f, 0L))
        assertTrue(
            "간격·거리 모두 이내이므로 더블탭",
            detector.onTap(100f + withinDistance, 100f, withinInterval),
        )
    }

    @Test
    fun `완전히 같은 좌표의 연속 탭도 더블탭이다`() {
        assertFalse(detector.onTap(50f, 50f, 0L))
        assertTrue(detector.onTap(50f, 50f, withinInterval))
    }

    // ---------------------------------------------------------------- 간격 초과

    @Test
    fun `간격을 넘기면 더블탭이 아니고 두 번째 탭이 새 직전 탭이 된다`() {
        assertFalse(detector.onTap(100f, 100f, 0L))
        assertFalse("간격 초과", detector.onTap(100f, 100f, beyondInterval))

        // 두 번째 탭이 새 기준점이 됐으므로, 그로부터 간격 이내인 세 번째 탭은 더블탭이다.
        assertTrue(detector.onTap(100f, 100f, beyondInterval + withinInterval))
    }

    @Test
    fun `간격 경계값은 이내로 인정된다`() {
        assertFalse(detector.onTap(10f, 10f, 1_000L))
        assertTrue(
            "DOUBLE_TAP_INTERVAL_MS는 '이내'이므로 경계값 포함",
            detector.onTap(10f, 10f, 1_000L + GestureConfig.DOUBLE_TAP_INTERVAL_MS),
        )
    }

    // ---------------------------------------------------------------- 거리 초과

    @Test
    fun `거리를 넘기면 간격이 이내여도 더블탭이 아니다`() {
        assertFalse(detector.onTap(100f, 100f, 0L))
        assertFalse(
            "간격은 이내지만 거리 초과",
            detector.onTap(100f + beyondDistance, 100f, withinInterval),
        )
    }

    @Test
    fun `거리 판정은 축이 아니라 직선 거리 기준이다`() {
        // x, y 각각은 허용 거리 이내지만 대각선 합성 거리는 초과하는 좌표.
        // (0.8 * D)^2 * 2 ≈ (1.13 * D)^2 > D^2
        val perAxis = GestureConfig.DOUBLE_TAP_DISTANCE_PX * 0.8f
        assertFalse(detector.onTap(0f, 0f, 0L))
        assertFalse(
            "각 축은 이내여도 합성 거리가 초과하면 더블탭이 아니다",
            detector.onTap(perAxis, perAxis, withinInterval),
        )
    }

    @Test
    fun `거리 경계값은 이내로 인정된다`() {
        assertFalse(detector.onTap(0f, 0f, 0L))
        assertTrue(
            "DOUBLE_TAP_DISTANCE_PX는 '이내'이므로 경계값 포함",
            detector.onTap(GestureConfig.DOUBLE_TAP_DISTANCE_PX, 0f, withinInterval),
        )
    }

    @Test
    fun `거리 초과로 실패한 탭도 새 직전 탭으로 갱신된다`() {
        assertFalse(detector.onTap(0f, 0f, 0L))
        assertFalse(detector.onTap(beyondDistance, 0f, withinInterval))

        // 두 번째 탭(beyondDistance 위치)이 기준이 됐으므로 그 근처 세 번째 탭은 더블탭이다.
        assertTrue(detector.onTap(beyondDistance, 0f, withinInterval * 2))
    }

    // ---------------------------------------------------------------- 확정 후 리셋

    @Test
    fun `더블탭 확정 후에는 상태가 비워져 세 번째 탭이 다시 묶이지 않는다`() {
        // A-B가 더블탭이면 C는 완전히 새로운 첫 탭이어야 한다.
        // 이게 없으면 세 번 탭했을 때 더블클릭이 두 번(A+B, B+C) 나간다.
        assertFalse("A", detector.onTap(100f, 100f, 0L))
        assertTrue("B — A와 묶여 더블탭", detector.onTap(100f, 100f, withinInterval))
        assertFalse(
            "C — 간격·거리가 이내여도 B는 이미 소진됐으므로 새 첫 탭이다",
            detector.onTap(100f, 100f, withinInterval * 2),
        )
    }

    @Test
    fun `네 번 탭하면 더블탭이 정확히 두 번 확정된다`() {
        val results = listOf(0L, 1L, 2L, 3L).map { i ->
            detector.onTap(100f, 100f, i * withinInterval)
        }

        // A(false) B(true) C(false) D(true) — 2회 탭마다 한 번씩만 확정
        assertEquals(listOf(false, true, false, true), results)
    }

    @Test
    fun `reset 후에는 직전 탭이 사라져 다음 탭이 첫 탭이 된다`() {
        assertFalse(detector.onTap(100f, 100f, 0L))
        detector.reset()
        assertFalse(
            "reset으로 직전 탭을 버렸으므로 묶일 상대가 없다",
            detector.onTap(100f, 100f, withinInterval),
        )
    }

    // ---------------------------------------------------------------- 방어

    @Test
    fun `시각이 거꾸로 오면 더블탭으로 묶지 않는다`() {
        // System.currentTimeMillis()는 단조 증가가 보장되지 않는다(시각 동기화 등).
        // 음수 간격을 그대로 통과시키면 아무리 오래된 탭과도 묶일 수 있다.
        assertFalse(detector.onTap(100f, 100f, 10_000L))
        assertFalse("음수 간격", detector.onTap(100f, 100f, 9_000L))
    }
}
