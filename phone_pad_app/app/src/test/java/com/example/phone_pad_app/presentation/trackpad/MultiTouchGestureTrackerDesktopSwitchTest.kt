package com.example.phone_pad_app.presentation.trackpad

import com.example.phone_pad_app.presentation.util.GestureConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 3손가락 수평 스와이프 → `DESKTOP_SWITCH` 판정 테스트 (Phase 5).
 *
 * 기존 [MultiTouchGestureTrackerTest]는 **무수정으로 통과해야 한다** — 3손가락 래치는
 * 1·2손가락 제스처의 동작을 바꾸지 않는다는 것이 확정 스펙이다.
 *
 * 임계값은 하드코딩하지 않고 전부 [GestureConfig]에서 파생시킨다.
 */
class MultiTouchGestureTrackerDesktopSwitchTest {

    private val one = GestureConfig.SINGLE_POINTER_COUNT
    private val two = GestureConfig.DOUBLE_POINTER_COUNT
    private val three = GestureConfig.THREE_POINTER_COUNT

    private val left = MultiTouchGestureTracker.DIRECTION_LEFT
    private val right = MultiTouchGestureTracker.DIRECTION_RIGHT

    /** 전환 임계를 확실히 넘기는 수평 이동 거리 */
    private val swipePx = GestureConfig.THREE_FINGER_SWIPE_MIN_DISTANCE_PX * 1.5f

    /** 탭 한계를 넘겨 MOVE/스크롤이 열리는 거리 */
    private val dragPx = GestureConfig.TAP_MAX_DISTANCE_PX * 3f

    private val stepPx = GestureConfig.SCROLL_SENSITIVITY_PX_PER_STEP

    private val tapDurationMs = GestureConfig.TAP_MAX_DURATION_MS / 2

    private val tracker = MultiTouchGestureTracker()

    // ---------------------------------------------------------------- 방향 매핑

    @Test
    fun `손가락을 왼쪽으로 쓸면 오른쪽 데스크톱으로 전환한다`() {
        // Windows 정밀 터치패드 관례: 콘텐츠가 손가락을 따라 밀려나며 오른쪽 데스크톱이 드러난다.
        // 이 뒤집기는 Android(트래커) 한 곳에서만 일어나고 서버는 받은 값을 그대로 쓴다.
        tracker.onPointerEvent(three, 500f, 300f, 0L)

        val decision = tracker.onPointerEvent(three, 500f - swipePx, 300f, 50L)

        assertEquals(right, decision.desktopSwitch)
    }

    @Test
    fun `손가락을 오른쪽으로 쓸면 왼쪽 데스크톱으로 전환한다`() {
        tracker.onPointerEvent(three, 100f, 300f, 0L)

        val decision = tracker.onPointerEvent(three, 100f + swipePx, 300f, 50L)

        assertEquals(left, decision.desktopSwitch)
    }

    @Test
    fun `방향 상수는 프로토콜 JSON의 direction 값과 같다`() {
        // AGENTS.md 섹션 4: {"type":"DESKTOP_SWITCH","direction":"left"|"right"}
        assertEquals("left", MultiTouchGestureTracker.DIRECTION_LEFT)
        assertEquals("right", MultiTouchGestureTracker.DIRECTION_RIGHT)
    }

    // ---------------------------------------------------------------- 임계 / 방향 조건

    @Test
    fun `임계 거리에 못 미치는 3손가락 이동은 전환하지 않는다`() {
        tracker.onPointerEvent(three, 0f, 0f, 0L)

        val justUnder = GestureConfig.THREE_FINGER_SWIPE_MIN_DISTANCE_PX - 1f
        assertNull(tracker.onPointerEvent(three, justUnder, 0f, 50L).desktopSwitch)
    }

    @Test
    fun `임계 거리에 정확히 도달하면 전환한다`() {
        // 판정은 "이상"이다 — 경계에서 아무 일도 안 일어나는 사각지대를 만들지 않는다.
        tracker.onPointerEvent(three, 0f, 0f, 0L)

        assertEquals(
            left,
            tracker.onPointerEvent(
                three,
                GestureConfig.THREE_FINGER_SWIPE_MIN_DISTANCE_PX,
                0f,
                50L,
            ).desktopSwitch,
        )
    }

    @Test
    fun `수직 스와이프는 전환하지 않는다`() {
        // 수직(작업 보기 등)은 이번 범위 밖 — 좌우 전환으로 오인하면 안 된다.
        tracker.onPointerEvent(three, 0f, 0f, 0L)

        assertNull(tracker.onPointerEvent(three, 0f, swipePx, 50L).desktopSwitch)
        assertNull(tracker.onPointerEvent(three, 0f, swipePx * 2f, 100L).desktopSwitch)
    }

    @Test
    fun `수평 우세가 모자란 대각선 스와이프는 전환하지 않는다`() {
        tracker.onPointerEvent(three, 0f, 0f, 0L)

        // |dx| == |dy| → 우세 배수(2배)에 미달
        assertNull(tracker.onPointerEvent(three, swipePx, swipePx, 50L).desktopSwitch)
    }

    @Test
    fun `수평 우세를 만족하는 완만한 대각선은 전환한다`() {
        tracker.onPointerEvent(three, 0f, 0f, 0L)

        // |dx| == 우세 배수 * |dy| → 경계에서 인정된다
        val dy = swipePx / GestureConfig.THREE_FINGER_SWIPE_HORIZONTAL_DOMINANCE
        assertEquals(left, tracker.onPointerEvent(three, swipePx, dy, 50L).desktopSwitch)
    }

    // ---------------------------------------------------------------- 구간당 1회

    @Test
    fun `한 구간에서는 계속 밀어도 한 번만 전환한다`() {
        tracker.onPointerEvent(three, 0f, 0f, 0L)

        assertEquals(left, tracker.onPointerEvent(three, swipePx, 0f, 50L).desktopSwitch)
        // 계속 미는 동안 데스크톱이 연달아 넘어가면 안 된다
        assertNull(tracker.onPointerEvent(three, swipePx * 2f, 0f, 100L).desktopSwitch)
        assertNull(tracker.onPointerEvent(three, swipePx * 3f, 0f, 150L).desktopSwitch)
    }

    @Test
    fun `되돌아와서 반대로 쓸어도 같은 구간에서는 전환하지 않는다`() {
        tracker.onPointerEvent(three, 0f, 0f, 0L)
        assertEquals(left, tracker.onPointerEvent(three, swipePx, 0f, 50L).desktopSwitch)

        assertNull(tracker.onPointerEvent(three, -swipePx, 0f, 100L).desktopSwitch)
    }

    @Test
    fun `구간이 새로 시작되면 다시 한 번 전환할 수 있다`() {
        // 3 → 2 → 3 으로 손가락 개수가 바뀌면 구간이 새로 시작된다(구간 단위 정의).
        tracker.onPointerEvent(three, 0f, 0f, 0L)
        assertEquals(left, tracker.onPointerEvent(three, swipePx, 0f, 50L).desktopSwitch)

        tracker.onPointerEvent(two, 0f, 0f, 100L)
        assertTrue(tracker.onPointerEvent(three, 0f, 0f, 150L).segmentStarted)

        assertEquals(right, tracker.onPointerEvent(three, -swipePx, 0f, 200L).desktopSwitch)
    }

    @Test
    fun `제스처가 끝나면 다음 제스처에서 다시 전환할 수 있다`() {
        tracker.onPointerEvent(three, 0f, 0f, 0L)
        assertEquals(left, tracker.onPointerEvent(three, swipePx, 0f, 50L).desktopSwitch)
        tracker.onGestureEnd(100L)

        tracker.onPointerEvent(three, 0f, 0f, 1_000L)
        assertEquals(left, tracker.onPointerEvent(three, swipePx, 0f, 1_050L).desktopSwitch)
    }

    // ---------------------------------------------------------------- 손가락 개수 조건

    @Test
    fun `4손가락 스와이프는 전환하지 않고 아무 이벤트도 내지 않는다`() {
        // 4손가락은 범위 밖 — 래치는 걸리므로 MOVE/SCROLL/클릭도 나가지 않는다.
        val four = GestureConfig.THREE_POINTER_COUNT + 1
        tracker.onPointerEvent(four, 0f, 0f, 0L)

        val decision = tracker.onPointerEvent(four, swipePx, 0f, 50L)
        assertNull(decision.desktopSwitch)
        assertNull(decision.move)
        assertNull(decision.scroll)

        assertNull(tracker.onGestureEnd(tapDurationMs).clickButton)
    }

    @Test
    fun `1손가락과 2손가락 스와이프는 데스크톱 전환을 내지 않는다`() {
        tracker.onPointerEvent(one, 0f, 0f, 0L)
        assertNull(tracker.onPointerEvent(one, swipePx, 0f, 50L).desktopSwitch)
        tracker.onGestureEnd(100L)

        tracker.onPointerEvent(two, 0f, 0f, 1_000L)
        assertNull(tracker.onPointerEvent(two, swipePx, 0f, 1_050L).desktopSwitch)
    }

    @Test
    fun `스와이프 없이 3손가락을 탭하면 아무 이벤트도 없다`() {
        tracker.onPointerEvent(three, 100f, 100f, 0L)
        val decision = tracker.onPointerEvent(three, 105f, 100f, 50L)

        assertNull(decision.desktopSwitch)
        assertNull(decision.move)
        assertNull(decision.scroll)
        assertNull("3손가락 탭은 클릭이 아니다(기존 동작 유지)", tracker.onGestureEnd(tapDurationMs).clickButton)
    }

    // ---------------------------------------------------------------- 3손가락 래치

    @Test
    fun `스와이프 후 손가락을 어긋나게 떼도 클릭이 새지 않는다`() {
        // 핵심 안전장치: 3 → 2 → 1 → 0 꼬리는 MULTI_TOUCH_RELEASE_GRACE_MS 밖이면
        // 예전 규칙으로는 "정상 1손가락 탭"으로 보여 좌클릭이 새어나간다.
        tracker.onPointerEvent(three, 500f, 300f, 0L)
        assertEquals(right, tracker.onPointerEvent(three, 500f - swipePx, 300f, 50L).desktopSwitch)

        // 유예 시간을 넉넉히 넘기는 꼬리
        val grace = GestureConfig.MULTI_TOUCH_RELEASE_GRACE_MS
        tracker.onPointerEvent(two, 340f, 300f, 100L)
        tracker.onPointerEvent(one, 330f, 300f, 100L + grace * 2)

        val end = tracker.onGestureEnd(100L + grace * 3)
        assertNull("3손가락 스와이프 직후 클릭이 새면 안 된다", end.clickButton)
    }

    @Test
    fun `꼬리가 유예 시간 안에 끝나도 우클릭으로 승격되지 않는다`() {
        // 3 → 2 로 줄어든 직후 끝나면 예전 꼬리 보정 규칙상 "2손가락 탭 = 우클릭"이 될 수 있다.
        tracker.onPointerEvent(three, 100f, 100f, 0L)
        tracker.onPointerEvent(two, 100f, 100f, 10L)

        val end = tracker.onGestureEnd(10L + GestureConfig.MULTI_TOUCH_RELEASE_GRACE_MS / 2)
        assertNull(end.clickButton)
    }

    @Test
    fun `래치 중에는 1손가락으로 줄어도 MOVE가 나가지 않는다`() {
        tracker.onPointerEvent(one, 0f, 0f, 0L)
        tracker.onPointerEvent(three, 0f, 0f, 10L)

        tracker.onPointerEvent(one, 0f, 0f, 20L)
        assertNull(
            "3손가락 꼬리의 1손가락 이동이 커서를 움직이면 안 된다",
            tracker.onPointerEvent(one, dragPx, 0f, 30L).move,
        )
    }

    @Test
    fun `2손가락 스크롤 중 3번째 손가락이 닿으면 이후 스크롤이 멈춘다`() {
        tracker.onPointerEvent(two, 0f, 0f, 0L)
        assertNotNull(
            "래치 전에는 정상적으로 스크롤한다",
            tracker.onPointerEvent(two, 0f, stepPx * 3f, 10L).scroll,
        )

        tracker.onPointerEvent(three, 0f, stepPx * 3f, 20L)

        // 다시 2손가락으로 돌아와 크게 움직여도 스크롤은 나가지 않는다
        tracker.onPointerEvent(two, 0f, stepPx * 3f, 30L)
        assertNull(tracker.onPointerEvent(two, 0f, stepPx * 9f, 40L).scroll)
        assertNull(tracker.onGestureEnd(50L).clickButton)
    }

    @Test
    fun `데스크톱 전환은 MOVE나 SCROLL과 동시에 방출되지 않는다`() {
        // 래치 덕분에 구조적으로 상호 배타다 — 서버가 한 제스처에서 두 종류의 명령을 받는 일이 없다.
        tracker.onPointerEvent(three, 0f, 0f, 0L)

        val decision = tracker.onPointerEvent(three, swipePx, 0f, 50L)
        assertNotNull(decision.desktopSwitch)
        assertNull(decision.move)
        assertNull(decision.scroll)
    }

    @Test
    fun `제스처가 끝나면 래치가 풀려 다음 제스처의 1손가락 탭이 정상 동작한다`() {
        tracker.onPointerEvent(three, 0f, 0f, 0L)
        tracker.onPointerEvent(three, swipePx, 0f, 50L)
        tracker.onGestureEnd(100L)

        tracker.onPointerEvent(one, 10f, 10f, 1_000L)
        val end = tracker.onGestureEnd(1_000L + tapDurationMs)
        assertEquals(MultiTouchGestureTracker.BUTTON_LEFT, end.clickButton)
    }

    @Test
    fun `reset도 래치를 푼다`() {
        tracker.onPointerEvent(three, 0f, 0f, 0L)
        tracker.reset()

        tracker.onPointerEvent(one, 0f, 0f, 1_000L)
        assertNotNull(tracker.onPointerEvent(one, dragPx, 0f, 1_010L).move)
    }

    @Test
    fun `제스처가 끝나면 다음 제스처의 2손가락 스크롤도 정상 동작한다`() {
        tracker.onPointerEvent(three, 0f, 0f, 0L)
        tracker.onPointerEvent(three, swipePx, 0f, 50L)
        tracker.onGestureEnd(100L)

        tracker.onPointerEvent(two, 0f, 0f, 1_000L)
        assertEquals(
            ScrollDelta(dx = 0, dy = 3),
            tracker.onPointerEvent(two, 0f, stepPx * 3f, 1_010L).scroll,
        )
    }
}
