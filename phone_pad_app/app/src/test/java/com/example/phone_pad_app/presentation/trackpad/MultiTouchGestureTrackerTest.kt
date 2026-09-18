package com.example.phone_pad_app.presentation.trackpad

import com.example.phone_pad_app.presentation.util.GestureConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 제스처 판정 로직 단위 테스트 — Compose 의존성 없이 순수 로직만 검증한다.
 * 임계값은 하드코딩하지 않고 전부 [GestureConfig]에서 파생시켜, 상수를 바꿔도 의도가 유지되는지 확인한다.
 */
class MultiTouchGestureTrackerTest {

    private val one = GestureConfig.SINGLE_POINTER_COUNT

    /** MOVE는 방출되지만 탭으로도 인정되는 미세 이동 (MOVE_MIN 미만) */
    private val jitterPx = GestureConfig.MOVE_MIN_DISTANCE_PX / 2f

    /** 탭 판정을 깨는(드래그로 만드는) 확실한 이동 거리 */
    private val dragPx = GestureConfig.TAP_MAX_DISTANCE_PX * 3f

    /** 탭으로 인정되는 지속 시간 */
    private val tapDurationMs = GestureConfig.TAP_MAX_DURATION_MS / 2

    private val tracker = MultiTouchGestureTracker()

    // ---------------------------------------------------------------- 1손가락 (기존 동작 회귀)

    @Test
    fun `1손가락 탭은 클릭으로 판정되고 이동은 방출되지 않는다`() {
        tracker.onPointerEvent(one, 100f, 100f, 0L)
        val moved = tracker.onPointerEvent(one, 100f + jitterPx, 100f, tapDurationMs / 2)

        assertNull("MOVE_MIN 미만 떨림은 이동을 방출하지 않는다", moved.move)

        val end = tracker.onGestureEnd(tapDurationMs)
        assertTrue("탭 거리·시간 이내이므로 클릭이어야 한다", end.click)
        assertEquals(one, end.pointerCount)
    }

    @Test
    fun `1손가락 드래그는 감도가 적용된 델타를 매 프레임 방출하고 종료 시 클릭하지 않는다`() {
        tracker.onPointerEvent(one, 0f, 0f, 0L)

        val first = tracker.onPointerEvent(one, dragPx, 0f, 20L)
        assertNotNull(first.move)
        assertEquals(dragPx * GestureConfig.MOVE_SENSITIVITY, first.move!!.dx, 0.001f)
        assertEquals(0f, first.move!!.dy, 0.001f)

        // 델타는 시작점이 아니라 직전 프레임 기준 (기존 positionChange() 동작과 동일)
        val second = tracker.onPointerEvent(one, dragPx * 2f, -dragPx, 40L)
        assertNotNull(second.move)
        assertEquals(dragPx * GestureConfig.MOVE_SENSITIVITY, second.move!!.dx, 0.001f)
        assertEquals(-dragPx * GestureConfig.MOVE_SENSITIVITY, second.move!!.dy, 0.001f)

        // 탭 시간 안에 끝나더라도 이동 거리가 TAP_MAX를 넘었으므로 클릭이 아니다
        val end = tracker.onGestureEnd(tapDurationMs)
        assertFalse(end.click)
    }

    @Test
    fun `1손가락이라도 탭 최대 지속 시간을 넘기면 클릭하지 않는다`() {
        tracker.onPointerEvent(one, 0f, 0f, 0L)
        val end = tracker.onGestureEnd(GestureConfig.TAP_MAX_DURATION_MS)
        assertFalse(end.click)
    }

    @Test
    fun `같은 좌표가 반복되면 이동을 방출하지 않는다`() {
        tracker.onPointerEvent(one, 10f, 10f, 0L)
        assertNull(tracker.onPointerEvent(one, 10f, 10f, 10L).move)
    }

    // ---------------------------------------------------------------- 손가락 개수 전환

    @Test
    fun `1에서 2손가락으로 바뀌면 이전 1손가락 구간이 취소되고 2손가락 구간은 아무것도 방출하지 않는다`() {
        // 여기까지는 그대로 끝났다면 탭으로 판정됐을 1손가락 구간
        tracker.onPointerEvent(one, 0f, 0f, 0L)
        assertNull(tracker.onPointerEvent(one, jitterPx, 0f, 10L).move)

        // 두 번째 손가락이 닿는 순간 → 구간 재시작, 방출 없음
        val restart = tracker.onPointerEvent(2, 500f, 500f, 20L)
        assertTrue("손가락 개수 변화는 새 구간을 시작해야 한다", restart.segmentStarted)
        assertNull(restart.move)
        assertEquals(2, restart.pointerCount)

        // 2손가락 구간에서 크게 움직여도 onMove는 방출되지 않는다 (이번 작업은 기반만)
        assertNull(tracker.onPointerEvent(2, 500f + dragPx, 500f, 40L).move)

        // 마지막 구간이 2손가락이므로 탭 시간·거리 조건과 무관하게 클릭 없음
        val end = tracker.onGestureEnd(50L)
        assertFalse("취소된 1손가락 구간의 탭이 살아나면 안 된다", end.click)
        assertEquals(2, end.pointerCount)
    }

    @Test
    fun `2에서 1손가락으로 바뀌면 그 시점부터 새 1손가락 구간이 시작되어 탭으로 판정된다`() {
        val liftTimeMs = GestureConfig.TAP_MAX_DURATION_MS * 5

        tracker.onPointerEvent(2, 0f, 0f, 0L)
        tracker.onPointerEvent(2, dragPx, dragPx, liftTimeMs / 2)

        // 손가락 하나가 먼저 떨어짐 → 남은 손가락 좌표/시각으로 새 구간 시작
        val restart = tracker.onPointerEvent(one, 300f, 300f, liftTimeMs)
        assertTrue(restart.segmentStarted)
        assertNull(restart.move)

        assertNull(tracker.onPointerEvent(one, 300f + jitterPx, 300f, liftTimeMs + 10).move)

        // 제스처 전체 길이는 탭 한계를 한참 넘지만, 마지막 1손가락 구간 기준이므로 탭이다
        val end = tracker.onGestureEnd(liftTimeMs + tapDurationMs)
        assertTrue("마지막 1손가락 구간 기준으로 탭 판정되어야 한다", end.click)
        assertEquals(one, end.pointerCount)
    }

    @Test
    fun `2에서 1손가락으로 바뀐 뒤 크게 움직이면 이동이 방출되고 클릭은 없다`() {
        tracker.onPointerEvent(2, 0f, 0f, 0L)
        tracker.onPointerEvent(one, 100f, 100f, 100L)

        val moved = tracker.onPointerEvent(one, 100f + dragPx, 100f, 120L)
        assertNotNull("전환 후 1손가락 구간은 정상적으로 이동을 방출해야 한다", moved.move)
        assertEquals(dragPx * GestureConfig.MOVE_SENSITIVITY, moved.move!!.dx, 0.001f)

        assertFalse(tracker.onGestureEnd(150L).click)
    }

    // ---------------------------------------------------------------- 멀티터치 전용

    @Test
    fun `처음부터 끝까지 2손가락이면 이동도 클릭도 방출되지 않는다`() {
        assertTrue(tracker.onPointerEvent(2, 0f, 0f, 0L).segmentStarted)
        assertNull(tracker.onPointerEvent(2, jitterPx, 0f, 10L).move)
        assertNull(tracker.onPointerEvent(2, dragPx, dragPx, 20L).move)

        val end = tracker.onGestureEnd(tapDurationMs)
        assertFalse(end.click)
        assertEquals(2, end.pointerCount)
    }

    @Test
    fun `3손가락 구간도 아무것도 방출하지 않는다`() {
        tracker.onPointerEvent(3, 0f, 0f, 0L)
        assertNull(tracker.onPointerEvent(3, dragPx, 0f, 10L).move)

        val end = tracker.onGestureEnd(tapDurationMs)
        assertFalse(end.click)
        assertEquals(3, end.pointerCount)
    }

    // ---------------------------------------------------------------- 상태 관리

    @Test
    fun `포인터 개수가 0이면 아무 상태도 바꾸지 않는다`() {
        tracker.onPointerEvent(one, 0f, 0f, 0L)

        val ignored = tracker.onPointerEvent(0, 999f, 999f, 10L)
        assertNull(ignored.move)
        assertFalse(ignored.segmentStarted)
        assertEquals(0, ignored.pointerCount)

        // 앞선 1손가락 구간이 그대로 유지되어 탭으로 끝난다
        assertTrue(tracker.onGestureEnd(tapDurationMs).click)
    }

    @Test
    fun `이벤트가 없었으면 종료 시 클릭하지 않는다`() {
        val end = tracker.onGestureEnd(0L)
        assertFalse(end.click)
        assertEquals(0, end.pointerCount)
    }

    @Test
    fun `종료 후에는 상태가 초기화되어 다음 제스처가 독립적으로 판정된다`() {
        tracker.onPointerEvent(one, 0f, 0f, 0L)
        tracker.onPointerEvent(one, dragPx, 0f, 10L)
        assertFalse(tracker.onGestureEnd(20L).click)

        // 새 제스처: 앞선 드래그 흔적이 남아 있으면 탭 판정이 깨진다
        tracker.onPointerEvent(one, 0f, 0f, 1_000L)
        assertTrue(tracker.onGestureEnd(1_000L + tapDurationMs).click)
    }
}
