package com.example.phone_pad_app.presentation.settings

import com.example.phone_pad_app.domain.model.GestureSettings

/**
 * "스크롤 속도" 슬라이더의 좌표 변환.
 *
 * 저장되는 값(`scrollPxPerStep`)은 **클수록 느린** 값이라 그대로 슬라이더에 물리면
 * 오른쪽으로 밀수록 느려져 직관과 반대가 된다. 그래서 허용 범위 안에서 값을 좌우로 뒤집는다:
 * 범위의 양 끝을 서로 바꾸는 대칭 변환이라 슬라이더의 `valueRange`는
 * [GestureSettings.SCROLL_PX_PER_STEP_RANGE] 그대로 쓸 수 있고, 변환이 자기 자신의 역함수다
 * (`fromSlider(toSlider(x)) == x`).
 *
 * Compose에 의존하지 않는 순수 함수라 단위 테스트로 고정한다 — UI에서 직접 계산하면
 * 한쪽 방향만 고치고 다른 방향을 잊는 실수가 나기 쉽다.
 */
object ScrollSpeedSlider {

    private val range = GestureSettings.SCROLL_PX_PER_STEP_RANGE

    /** 범위의 중심을 기준으로 값을 뒤집는다 — 하한↔상한이 서로 맞바뀐다. */
    private fun mirror(value: Float): Float =
        (range.start + range.endInclusive - value).coerceIn(range)

    /** 저장값(px/step) → 슬라이더 위치(오른쪽일수록 빠름). */
    fun toSliderValue(pxPerStep: Float): Float = mirror(pxPerStep)

    /** 슬라이더 위치 → 저장값(px/step). */
    fun toPxPerStep(sliderValue: Float): Float = mirror(sliderValue)
}
