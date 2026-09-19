package com.example.phone_pad_app.presentation.trackpad

import com.example.phone_pad_app.domain.model.GestureSettings
import com.example.phone_pad_app.presentation.util.GestureConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * 주입된 감도 설정이 실제 판정에 반영되는지 확인한다.
 *
 * 기존 [MultiTouchGestureTrackerTest]는 기본 생성자만 쓰며 **무수정으로 통과해야 한다** —
 * 생성자 기본값이 [GestureConfig] 상수와 같다는 것이 그 전제이고, 이 파일의 첫 테스트가 그것을 고정한다.
 */
class MultiTouchGestureTrackerSettingsTest {

    private val one = GestureConfig.SINGLE_POINTER_COUNT
    private val two = GestureConfig.DOUBLE_POINTER_COUNT

    /** 탭 한계를 확실히 넘겨 MOVE/스크롤이 열리는 이동 거리 */
    private val dragPx = GestureConfig.TAP_MAX_DISTANCE_PX * 3f

    private val defaultStepPx = GestureConfig.SCROLL_SENSITIVITY_PX_PER_STEP

    @Test
    fun `기본 생성자는 GestureConfig 상수를 그대로 쓴다`() {
        val tracker = MultiTouchGestureTracker()
        tracker.onPointerEvent(one, 0f, 0f, 0L)

        val move = tracker.onPointerEvent(one, dragPx, 0f, 10L).move

        assertNotNull(move)
        assertEquals(dragPx * GestureConfig.MOVE_SENSITIVITY, move!!.dx, 0.001f)
    }

    @Test
    fun `이동 감도를 2배로 주입하면 같은 이동에서 델타도 2배가 된다`() {
        val doubled = MultiTouchGestureTracker(
            moveSensitivity = GestureConfig.MOVE_SENSITIVITY * 2f,
            scrollPxPerStep = defaultStepPx,
        )
        doubled.onPointerEvent(one, 0f, 0f, 0L)

        val move = doubled.onPointerEvent(one, dragPx, -dragPx, 10L).move

        assertNotNull(move)
        assertEquals(dragPx * GestureConfig.MOVE_SENSITIVITY * 2f, move!!.dx, 0.001f)
        assertEquals(-dragPx * GestureConfig.MOVE_SENSITIVITY * 2f, move.dy, 0.001f)
    }

    @Test
    fun `이동 감도는 MOVE 최소 거리 판정에는 영향을 주지 않는다`() {
        // 감도는 "얼마나 움직였는지"가 아니라 "얼마나 보낼지"에만 곱해진다 — 감도를 올렸다고
        // 떨림 억제(MOVE_MIN_DISTANCE_PX) 문턱이 낮아지면 손을 떨 때마다 커서가 튄다.
        val sensitive = MultiTouchGestureTracker(moveSensitivity = 4f, scrollPxPerStep = defaultStepPx)
        sensitive.onPointerEvent(one, 0f, 0f, 0L)

        val jitter = GestureConfig.MOVE_MIN_DISTANCE_PX / 2f
        assertNull(sensitive.onPointerEvent(one, jitter, 0f, 10L).move)
    }

    @Test
    fun `스크롤 스텝 거리를 절반으로 주입하면 같은 이동에서 스텝이 2배가 된다`() {
        val half = MultiTouchGestureTracker(
            moveSensitivity = GestureConfig.MOVE_SENSITIVITY,
            scrollPxPerStep = defaultStepPx / 2f,
        )
        half.onPointerEvent(two, 0f, 0f, 0L)

        // 기본 감도라면 3스텝이 나올 거리
        val scroll = half.onPointerEvent(two, 0f, defaultStepPx * 3f, 10L).scroll

        assertEquals(ScrollDelta(dx = 0, dy = 6), scroll)
    }

    @Test
    fun `스크롤 스텝 거리를 늘리면 같은 이동에서 스텝이 줄어든다`() {
        val slow = MultiTouchGestureTracker(
            moveSensitivity = GestureConfig.MOVE_SENSITIVITY,
            scrollPxPerStep = defaultStepPx * 2f,
        )
        slow.onPointerEvent(two, 0f, 0f, 0L)

        assertEquals(
            ScrollDelta(dx = 0, dy = 2),
            slow.onPointerEvent(two, 0f, defaultStepPx * 4f, 10L).scroll,
        )
    }

    @Test
    fun `주입된 스텝 거리에서도 잔차가 누적되어 스텝이 손실되지 않는다`() {
        val stepPx = GestureConfig.SCROLL_PX_PER_STEP_MIN
        val tracker = MultiTouchGestureTracker(scrollPxPerStep = stepPx)
        tracker.onPointerEvent(two, 0f, 0f, 0L)
        // 스크롤 개시 (정확히 1스텝, 잔차 0)
        assertEquals(ScrollDelta(dx = 0, dy = 1), tracker.onPointerEvent(two, 0f, stepPx, 10L).scroll)

        var total = 0
        val frameDy = stepPx / 4f
        for (i in 1..40) {
            total += tracker.onPointerEvent(two, 0f, stepPx + frameDy * i, 10L + i).scroll?.dy ?: 0
        }

        assertEquals(10, total)
    }

    @Test
    fun `GestureSettings 생성자는 필드를 그대로 전달한다`() {
        val settings = GestureSettings(moveSensitivity = 3f, scrollPxPerStep = 80f)
        val fromSettings = MultiTouchGestureTracker(settings)
        val fromFields = MultiTouchGestureTracker(
            moveSensitivity = settings.moveSensitivity,
            scrollPxPerStep = settings.scrollPxPerStep,
        )

        fromSettings.onPointerEvent(one, 0f, 0f, 0L)
        fromFields.onPointerEvent(one, 0f, 0f, 0L)
        assertEquals(
            fromFields.onPointerEvent(one, dragPx, 0f, 10L).move,
            fromSettings.onPointerEvent(one, dragPx, 0f, 10L).move,
        )

        fromSettings.onGestureEnd(20L)
        fromSettings.onPointerEvent(two, 0f, 0f, 100L)
        assertEquals(
            ScrollDelta(dx = 0, dy = 2),
            fromSettings.onPointerEvent(two, 0f, 160f, 110L).scroll, // 160px / 80px per step
        )
    }

    @Test
    fun `감도 설정은 탭 우클릭 판정에 영향을 주지 않는다`() {
        // 조정 가능한 두 값은 "얼마나 보낼지"만 바꾼다 — 탭/우클릭 판정(거리·시간)은 그대로다.
        val tracker = MultiTouchGestureTracker(moveSensitivity = 4f, scrollPxPerStep = 25f)
        tracker.onPointerEvent(two, 100f, 100f, 0L)

        val end = tracker.onGestureEnd(GestureConfig.TAP_MAX_DURATION_MS / 2)

        assertEquals(MultiTouchGestureTracker.BUTTON_RIGHT, end.clickButton)
    }

    @Test
    fun `스크롤 스텝 거리가 0 이하면 생성 자체를 거부한다`() {
        // 0으로 나누면 Infinity 스텝이 서버로 나간다 — 조용히 통과시키면 안 된다.
        assertThrows(IllegalArgumentException::class.java) {
            MultiTouchGestureTracker(scrollPxPerStep = 0f)
        }
        assertThrows(IllegalArgumentException::class.java) {
            MultiTouchGestureTracker(scrollPxPerStep = -40f)
        }
    }
}
