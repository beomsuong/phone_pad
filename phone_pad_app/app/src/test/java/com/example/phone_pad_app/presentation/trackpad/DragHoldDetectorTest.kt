package com.example.phone_pad_app.presentation.trackpad

import com.example.phone_pad_app.presentation.util.GestureConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 탭홀드 → 드래그 승격 판정 테스트.
 *
 * 코루틴 타이머(`withTimeoutOrNull` 경합)는 `TrackpadScreen`에 남아 있지만, **승격할지 말지의
 * 판정 자체**는 전부 이 순수 클래스에 있으므로 시간을 흘려보내지 않고 타임스탬프만 넘겨
 * 경계 조건을 고정할 수 있다.
 */
class DragHoldDetectorTest {

    private val threshold = GestureConfig.DRAG_HOLD_THRESHOLD_MS
    private val tapDistance = GestureConfig.TAP_MAX_DISTANCE_PX

    private fun armedDetector(startMs: Long = 0L): DragHoldDetector =
        DragHoldDetector().apply { onGestureStart(x = 100f, y = 100f, timestampMs = startMs) }

    // --- 승격 ---------------------------------------------------------------------

    @Test
    fun `제자리를 유지한 채 임계 시간이 지나면 승격한다`() {
        val detector = armedDetector()

        assertEquals(DragHoldSignal.Start, detector.onHoldTimeout(threshold))
        assertTrue(detector.isActive)
        assertTrue(detector.hasPromoted)
    }

    @Test
    fun `임계 시간 직전에는 승격하지 않는다`() {
        val detector = armedDetector()

        assertEquals(DragHoldSignal.None, detector.onHoldTimeout(threshold - 1))
        assertFalse(detector.isActive)
        assertFalse(detector.hasPromoted)
    }

    @Test
    fun `승격은 임계 시각 정확히 그 순간부터 성립한다`() {
        // DRAG_HOLD_THRESHOLD_MS == TAP_MAX_DURATION_MS 이고, 탭 판정은 elapsed < TAP_MAX_DURATION_MS
        // 이므로 경계값(200ms)은 "탭이 아니고 드래그 홀드"가 되어야 사각지대가 없다.
        assertEquals(DragHoldSignal.None, armedDetector().onHoldTimeout(threshold - 1))
        assertEquals(DragHoldSignal.Start, armedDetector().onHoldTimeout(threshold))
    }

    @Test
    fun `탭 한계 거리 이내의 떨림은 승격을 막지 않는다`() {
        val detector = armedDetector()

        // 경계값(정확히 TAP_MAX_DISTANCE_PX)은 "이내"로 취급한다 — 트래커의 isDrag 판정과 동일
        val moved = detector.onPointerEvent(
            pointerCount = 1,
            x = 100f + tapDistance,
            y = 100f,
            timestampMs = threshold / 2,
        )
        assertEquals(DragHoldSignal.None, moved)

        assertEquals(DragHoldSignal.Start, detector.onHoldTimeout(threshold))
    }

    @Test
    fun `떨림 이벤트가 계속 들어와도 시간이 차면 이벤트 경로에서 승격한다`() {
        // 타이머 경합만으로는 "이벤트가 임계 시각 직후에 도착한" 경우를 놓칠 수 있다.
        val detector = armedDetector()

        detector.onPointerEvent(1, 101f, 100f, threshold - 10)
        val signal = detector.onPointerEvent(1, 102f, 100f, threshold + 5)

        assertEquals(DragHoldSignal.Start, signal)
        assertTrue(detector.isActive)
    }

    // --- 미승격 (기존 커서 이동 회귀 방지) --------------------------------------------

    @Test
    fun `시간이 되기 전에 탭 한계를 넘게 움직이면 승격하지 않는다`() {
        val detector = armedDetector()

        val moved = detector.onPointerEvent(
            pointerCount = 1,
            x = 100f + tapDistance + 1f,
            y = 100f,
            timestampMs = threshold / 2,
        )

        assertEquals(DragHoldSignal.None, moved)
        // 이후 아무리 시간이 지나도 승격하지 않는다 — 기존처럼 버튼 없는 MOVE로만 처리된다
        assertEquals(DragHoldSignal.None, detector.onHoldTimeout(threshold * 10))
        assertFalse(detector.isActive)
        assertFalse(detector.hasPromoted)
    }

    @Test
    fun `거리 초과 판정은 sticky하다 - 제자리로 돌아와도 승격하지 않는다`() {
        val detector = armedDetector()

        detector.onPointerEvent(1, 100f + tapDistance + 50f, 100f, 20L)
        detector.onPointerEvent(1, 100f, 100f, 40L) // 시작점으로 복귀

        assertEquals(DragHoldSignal.None, detector.onHoldTimeout(threshold + 100))
        assertFalse(detector.hasPromoted)
    }

    @Test
    fun `승격 전 종료는 아무 신호도 내지 않는다 - 짧은 탭은 그대로 클릭 경로로 간다`() {
        val detector = armedDetector()

        detector.onPointerEvent(1, 101f, 101f, 50L)

        assertEquals(DragHoldSignal.None, detector.onGestureEnd())
        assertFalse(detector.hasPromoted)
    }

    // --- 해제 ---------------------------------------------------------------------

    @Test
    fun `승격 후 손가락을 떼면 DRAG_END를 낸다`() {
        val detector = armedDetector()
        detector.onHoldTimeout(threshold)

        assertEquals(DragHoldSignal.End, detector.onGestureEnd())
        assertFalse(detector.isActive)
        // 이 제스처가 드래그였다는 사실은 남는다 — 호출부가 클릭 판정을 건너뛰는 근거다
        assertTrue(detector.hasPromoted)
    }

    @Test
    fun `onGestureEnd는 멱등이다 - 정상 종료 후 finally에서 다시 불러도 중복 END가 없다`() {
        val detector = armedDetector()
        detector.onHoldTimeout(threshold)

        assertEquals(DragHoldSignal.End, detector.onGestureEnd())
        assertEquals(DragHoldSignal.None, detector.onGestureEnd())
    }

    @Test
    fun `드래그 중 손가락이 추가되면 즉시 DRAG_END를 낸다`() {
        val detector = armedDetector()
        detector.onHoldTimeout(threshold)

        val signal = detector.onPointerEvent(
            pointerCount = GestureConfig.DOUBLE_POINTER_COUNT,
            x = 150f,
            y = 150f,
            timestampMs = threshold + 50,
        )

        assertEquals(DragHoldSignal.End, signal)
        assertFalse(detector.isActive)
    }

    @Test
    fun `손가락 개수가 변한 뒤에는 다시 1손가락이 되어도 재무장하지 않는다`() {
        // 2손가락 스크롤 뒤 손가락을 하나씩 떼는 꼬리 구간이 "제자리 1손가락 유지"로 보여
        // 드래그가 오발동하는 것을 막는다 (스크롤 뒤 클릭 오발동 방지와 같은 성격).
        val detector = armedDetector()

        detector.onPointerEvent(GestureConfig.DOUBLE_POINTER_COUNT, 120f, 120f, 30L)
        detector.onPointerEvent(1, 120f, 120f, 60L)

        assertNull(detector.remainingHoldMs(60L))
        assertEquals(DragHoldSignal.None, detector.onHoldTimeout(threshold * 5))
        assertEquals(DragHoldSignal.None, detector.onGestureEnd())
        assertFalse(detector.hasPromoted)
    }

    @Test
    fun `2손가락으로 시작한 제스처는 승격 후보가 아니다`() {
        val detector = armedDetector()

        detector.onPointerEvent(GestureConfig.DOUBLE_POINTER_COUNT, 100f, 100f, 10L)

        assertEquals(DragHoldSignal.None, detector.onHoldTimeout(threshold + 1000))
        assertFalse(detector.hasPromoted)
    }

    // --- 승격 후 동작 ---------------------------------------------------------------

    @Test
    fun `승격 후 이동해도 DRAG_START가 다시 나가지 않는다`() {
        val detector = armedDetector()
        detector.onHoldTimeout(threshold)

        val far = detector.onPointerEvent(1, 500f, 500f, threshold + 100)
        val again = detector.onHoldTimeout(threshold + 200)

        assertEquals(DragHoldSignal.None, far)
        assertEquals(DragHoldSignal.None, again)
        assertTrue(detector.isActive)
    }

    @Test
    fun `승격 직후 움직이지 않고 떼면 START와 END만 나간다`() {
        // 확정 스펙 8번: 서버 관점에서 일반 좌클릭의 down-up과 같은 결과라 문제 없다.
        val detector = armedDetector()

        assertEquals(DragHoldSignal.Start, detector.onHoldTimeout(threshold))
        assertEquals(DragHoldSignal.End, detector.onGestureEnd())
    }

    // --- 타이머 남은 시간 -------------------------------------------------------------

    @Test
    fun `무장 직후 남은 홀드 시간은 임계값 전체다`() {
        val detector = armedDetector(startMs = 1_000L)

        assertEquals(threshold, detector.remainingHoldMs(1_000L))
        assertEquals(threshold - 50L, detector.remainingHoldMs(1_050L))
    }

    @Test
    fun `임계를 이미 넘긴 상태의 남은 시간은 음수가 아니라 0이다`() {
        // 호출부가 withTimeoutOrNull에 그대로 넘기므로 음수가 새면 안 된다.
        val detector = armedDetector()

        assertEquals(0L, detector.remainingHoldMs(threshold + 500))
    }

    @Test
    fun `승격 후에는 남은 시간이 null이다 - 타임아웃 없이 이벤트만 기다린다`() {
        val detector = armedDetector()
        detector.onHoldTimeout(threshold)

        assertNull(detector.remainingHoldMs(threshold + 1))
    }

    @Test
    fun `거리 초과로 탈락한 뒤에는 남은 시간이 null이다`() {
        val detector = armedDetector()
        detector.onPointerEvent(1, 100f + tapDistance + 1f, 100f, 10L)

        assertNull(detector.remainingHoldMs(20L))
    }

    @Test
    fun `제스처 종료 후에는 남은 시간이 null이다`() {
        val detector = armedDetector()
        detector.onGestureEnd()

        assertNull(detector.remainingHoldMs(10L))
    }

    // --- 재사용 -------------------------------------------------------------------

    @Test
    fun `새 제스처가 시작되면 이전 제스처의 승격 이력이 지워진다`() {
        val detector = armedDetector()
        detector.onHoldTimeout(threshold)
        detector.onGestureEnd()

        detector.onGestureStart(x = 300f, y = 300f, timestampMs = 10_000L)

        assertFalse(detector.hasPromoted)
        assertFalse(detector.isActive)
        assertEquals(threshold, detector.remainingHoldMs(10_000L))
    }

    @Test
    fun `reset은 모든 상태를 지운다`() {
        val detector = armedDetector()
        detector.onHoldTimeout(threshold)

        detector.reset()

        assertFalse(detector.isActive)
        assertFalse(detector.hasPromoted)
        assertNull(detector.remainingHoldMs(threshold + 1))
    }

    // --- 탭 판정과의 상호 배타 ---------------------------------------------------------

    @Test
    fun `승격이 성립하는 제스처는 트래커가 탭으로 판정하지 않는다`() {
        // 두 판정이 같은 시간 기준(TAP_MAX_DURATION_MS)을 공유하므로 CLICK과 DRAG가
        // 동시에 나갈 수 없다는 것을 실제 두 클래스를 함께 돌려 고정한다.
        val detector = armedDetector(startMs = 0L)
        val tracker = MultiTouchGestureTracker()
        tracker.onPointerEvent(pointerCount = 1, x = 100f, y = 100f, timestampMs = 0L)

        assertEquals(DragHoldSignal.Start, detector.onHoldTimeout(threshold))

        val end = tracker.onGestureEnd(threshold)
        assertNull("드래그 홀드로 승격된 제스처는 탭이 아니다", end.clickButton)
    }

    @Test
    fun `탭으로 끝나는 짧은 제스처는 승격하지 않는다`() {
        val detector = armedDetector(startMs = 0L)
        val tracker = MultiTouchGestureTracker()
        tracker.onPointerEvent(pointerCount = 1, x = 100f, y = 100f, timestampMs = 0L)

        val tapEndMs = threshold - 1
        assertEquals(DragHoldSignal.None, detector.onHoldTimeout(tapEndMs))
        assertEquals(DragHoldSignal.None, detector.onGestureEnd())

        val end = tracker.onGestureEnd(tapEndMs)
        assertEquals(MultiTouchGestureTracker.BUTTON_LEFT, end.clickButton)
    }
}
