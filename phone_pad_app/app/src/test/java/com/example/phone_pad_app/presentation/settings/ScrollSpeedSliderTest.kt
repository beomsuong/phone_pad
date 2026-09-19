package com.example.phone_pad_app.presentation.settings

import com.example.phone_pad_app.domain.model.GestureSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "스크롤 속도" 슬라이더의 방향 뒤집기 검증.
 *
 * 저장값은 클수록 느린 px/step인데 슬라이더는 오른쪽이 빠름이어야 한다. 한 방향만 뒤집고
 * 반대 방향을 잊으면 설정 화면을 열 때마다 값이 반대편으로 튀는 버그가 된다.
 */
class ScrollSpeedSliderTest {

    private val range = GestureSettings.SCROLL_PX_PER_STEP_RANGE

    @Test
    fun `두 변환은 서로의 역함수다`() {
        listOf(range.start, 40f, 62.5f, range.endInclusive).forEach { px ->
            assertEquals(px, ScrollSpeedSlider.toPxPerStep(ScrollSpeedSlider.toSliderValue(px)), 0.0001f)
        }
    }

    @Test
    fun `슬라이더 양 끝은 저장값의 양 끝과 뒤바뀌어 대응한다`() {
        // 슬라이더 왼쪽 끝(가장 느림) = px/step 상한
        assertEquals(range.endInclusive, ScrollSpeedSlider.toPxPerStep(range.start), 0.0001f)
        // 슬라이더 오른쪽 끝(가장 빠름) = px/step 하한
        assertEquals(range.start, ScrollSpeedSlider.toPxPerStep(range.endInclusive), 0.0001f)
    }

    @Test
    fun `슬라이더를 오른쪽으로 옮길수록 px per step이 줄어든다 - 즉 빨라진다`() {
        val left = ScrollSpeedSlider.toPxPerStep(range.start)
        val middle = ScrollSpeedSlider.toPxPerStep((range.start + range.endInclusive) / 2f)
        val right = ScrollSpeedSlider.toPxPerStep(range.endInclusive)

        assertTrue(left > middle)
        assertTrue(middle > right)
    }

    @Test
    fun `변환 결과는 항상 허용 범위 안이다`() {
        // 부동소수 오차로 범위를 아주 살짝 벗어난 값이 Slider valueRange 밖으로 나가면
        // Compose Slider가 thumb 위치를 잘라 표시 값과 저장 값이 어긋난다.
        listOf(range.start, range.endInclusive, 40f, 33.3f).forEach { value ->
            assertTrue(ScrollSpeedSlider.toSliderValue(value) in range)
            assertTrue(ScrollSpeedSlider.toPxPerStep(value) in range)
        }
    }

    @Test
    fun `기본값은 범위 중앙보다 빠른 쪽에 있다`() {
        // 기본 40px/step은 하한(25) 쪽에 가깝다 — 슬라이더가 절반보다 오른쪽에서 시작한다는 뜻.
        val center = (range.start + range.endInclusive) / 2f
        assertTrue(GestureSettings.DEFAULT.scrollPxPerStep < center)
        assertTrue(ScrollSpeedSlider.toSliderValue(GestureSettings.DEFAULT.scrollPxPerStep) > center)
    }
}
