package com.example.phone_pad_app.domain.model

import com.example.phone_pad_app.presentation.util.GestureConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 감도 설정 값 객체의 불변식 테스트.
 *
 * 핵심은 두 가지다:
 * 1. 기본값이 [GestureConfig] 상수와 **같은 값**이어야 한다 — 상수가 기본값의 단일 출처이고,
 *    설정을 한 번도 건드리지 않은 사용자는 기존과 완전히 동일하게 동작해야 한다.
 * 2. 어떤 값이 들어와도 [GestureSettings.sanitized]를 거치면 제스처 판정기에 안전한 값이 된다.
 */
class GestureSettingsTest {

    @Test
    fun `기본값은 GestureConfig 상수와 일치한다`() {
        assertEquals(
            GestureConfig.MOVE_SENSITIVITY,
            GestureSettings.DEFAULT.moveSensitivity,
            0.0001f,
        )
        assertEquals(
            GestureConfig.SCROLL_SENSITIVITY_PX_PER_STEP,
            GestureSettings.DEFAULT.scrollPxPerStep,
            0.0001f,
        )
        // 인자 없는 생성 == DEFAULT (기본값이 두 군데로 갈라지지 않게)
        assertEquals(GestureSettings.DEFAULT, GestureSettings())
    }

    @Test
    fun `기본값은 허용 범위 안에 있다`() {
        assertTrue(GestureSettings.DEFAULT.moveSensitivity in GestureSettings.MOVE_SENSITIVITY_RANGE)
        assertTrue(GestureSettings.DEFAULT.scrollPxPerStep in GestureSettings.SCROLL_PX_PER_STEP_RANGE)
    }

    @Test
    fun `허용 범위는 하한이 상한보다 작고 하한은 양수다`() {
        assertTrue(
            GestureSettings.MOVE_SENSITIVITY_RANGE.start <
                GestureSettings.MOVE_SENSITIVITY_RANGE.endInclusive
        )
        assertTrue(
            GestureSettings.SCROLL_PX_PER_STEP_RANGE.start <
                GestureSettings.SCROLL_PX_PER_STEP_RANGE.endInclusive
        )
        // 0 이하가 되면 커서가 아예 멈추거나(배율 0) 0으로 나누기가 된다(px/step)
        assertTrue(GestureSettings.MOVE_SENSITIVITY_RANGE.start > 0f)
        assertTrue(GestureSettings.SCROLL_PX_PER_STEP_RANGE.start > 0f)
    }

    @Test
    fun `스크롤 하한은 탭 최대 이동 거리보다 크다`() {
        // 확정 스펙의 핵심 불변식: 사용자가 슬라이더를 끝까지 내려도
        // SCROLL_SENSITIVITY_PX_PER_STEP > TAP_MAX_DISTANCE_PX가 유지되어야 한다.
        // 깨지면 스크롤이 걸리는 순간 이미 1스텝 이상 쌓여 첫 프레임에 툭 튄다.
        assertTrue(
            "스크롤 px/step 하한이 탭 한계보다 작으면 스크롤 진입이 튄다",
            GestureSettings.SCROLL_PX_PER_STEP_RANGE.start > GestureConfig.TAP_MAX_DISTANCE_PX,
        )
    }

    @Test
    fun `범위 안의 값은 그대로 유지된다`() {
        val settings = GestureSettings(moveSensitivity = 2.25f, scrollPxPerStep = 55f)

        assertEquals(settings, settings.sanitized())
    }

    @Test
    fun `범위를 벗어난 값은 가까운 경계로 clamp 된다`() {
        val tooHigh = GestureSettings(
            moveSensitivity = GestureConfig.MOVE_SENSITIVITY_MAX + 10f,
            scrollPxPerStep = GestureConfig.SCROLL_PX_PER_STEP_MAX + 500f,
        ).sanitized()
        assertEquals(GestureConfig.MOVE_SENSITIVITY_MAX, tooHigh.moveSensitivity, 0.0001f)
        assertEquals(GestureConfig.SCROLL_PX_PER_STEP_MAX, tooHigh.scrollPxPerStep, 0.0001f)

        val tooLow = GestureSettings(
            moveSensitivity = -3f,
            scrollPxPerStep = 0f,
        ).sanitized()
        assertEquals(GestureConfig.MOVE_SENSITIVITY_MIN, tooLow.moveSensitivity, 0.0001f)
        assertEquals(GestureConfig.SCROLL_PX_PER_STEP_MIN, tooLow.scrollPxPerStep, 0.0001f)
    }

    @Test
    fun `NaN은 경계가 아니라 기본값으로 되돌아간다`() {
        // coerceIn은 비교 연산 기반이라 NaN을 그대로 통과시킨다 — 그 값이 판정기에 들어가면
        // 모든 델타가 NaN이 되어 커서가 영원히 멈춘다. 반드시 별도 처리가 필요하다.
        val sanitized = GestureSettings(
            moveSensitivity = Float.NaN,
            scrollPxPerStep = Float.NaN,
        ).sanitized()

        assertEquals(GestureSettings.DEFAULT, sanitized)
    }

    @Test
    fun `무한대도 기본값으로 되돌아간다`() {
        val positive = GestureSettings(
            moveSensitivity = Float.POSITIVE_INFINITY,
            scrollPxPerStep = Float.POSITIVE_INFINITY,
        ).sanitized()
        val negative = GestureSettings(
            moveSensitivity = Float.NEGATIVE_INFINITY,
            scrollPxPerStep = Float.NEGATIVE_INFINITY,
        ).sanitized()

        assertEquals(GestureSettings.DEFAULT, positive)
        assertEquals(GestureSettings.DEFAULT, negative)
    }

    @Test
    fun `clamp는 두 필드를 독립적으로 처리한다`() {
        // 한쪽만 오염돼 있을 때 멀쩡한 값까지 기본값으로 되돌리면 사용자가 설정을 잃는다.
        val sanitized = GestureSettings(
            moveSensitivity = Float.NaN,
            scrollPxPerStep = 55f,
        ).sanitized()

        assertEquals(GestureConfig.MOVE_SENSITIVITY, sanitized.moveSensitivity, 0.0001f)
        assertEquals(55f, sanitized.scrollPxPerStep, 0.0001f)
    }

    @Test
    fun `sanitized는 멱등이다`() {
        val once = GestureSettings(moveSensitivity = 99f, scrollPxPerStep = Float.NaN).sanitized()

        assertEquals(once, once.sanitized())
    }
}
