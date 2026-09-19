package com.example.phone_pad_app.domain.model

import com.example.phone_pad_app.presentation.util.GestureConfig

/**
 * 사용자가 조정할 수 있는 제스처 감도 설정.
 *
 * `GestureConfig`의 수많은 상수 중 **딱 두 개만** 여기로 끌어올렸다. 나머지(탭 시간/거리, 더블탭
 * 간격, 드래그 홀드 시간, 해제 유예)는 서로 얽힌 불변식이 있어서
 * (`DRAG_HOLD_THRESHOLD_MS == TAP_MAX_DURATION_MS`, `MULTI_TOUCH_RELEASE_GRACE_MS << TAP_MAX_DURATION_MS`,
 * `SCROLL_SENSITIVITY_PX_PER_STEP > TAP_MAX_DISTANCE_PX`) 사용자가 한쪽만 움직이면 탭/드래그
 * 사각지대나 오발동이 생긴다. 조정 가능한 두 값은 그런 불변식에 걸리지 않거나
 * ([scrollPxPerStep]의 하한이 마지막 불변식을 보호한다) 단순 배율이라 안전하다.
 *
 * 기본값은 [GestureConfig] 상수를 그대로 참조한다 — 상수가 여전히 **기본값의 단일 출처**다.
 *
 * 순수 Kotlin(Android/Compose/DataStore 비의존)이라 JUnit으로 그대로 검증할 수 있고,
 * 저장소에서 읽은 값이 오염돼 있어도 [sanitized]를 거치면 항상 유효한 값이 된다.
 */
data class GestureSettings(
    /** 커서 이동 배율. [MOVE_SENSITIVITY_RANGE] 안의 값. */
    val moveSensitivity: Float = GestureConfig.MOVE_SENSITIVITY,
    /**
     * 휠 1스텝에 해당하는 centroid 이동 거리 (px). [SCROLL_PX_PER_STEP_RANGE] 안의 값.
     *
     * **값이 클수록 스크롤이 느리다** — 같은 거리를 움직여도 더 적은 스텝이 나가기 때문이다.
     * UI에서 "스크롤 속도" 슬라이더로 보여줄 때는 방향을 뒤집어야 사용자 직관과 맞는다.
     */
    val scrollPxPerStep: Float = GestureConfig.SCROLL_SENSITIVITY_PX_PER_STEP,
) {

    /**
     * 범위 밖/비정상 값을 보정한 복사본을 돌려준다. 이미 유효하면 같은 값이다.
     *
     * 저장소에서 읽은 값(과거 버전이 저장한 값, 손상된 파일, 수동 편집 등)이 그대로 제스처
     * 판정기로 흘러들어가면 커서가 아예 안 움직이거나(0배) 0으로 나누는 사고가 난다.
     * 읽기 경로와 쓰기 경로 양쪽에서 이걸 통과시키는 것이 유일한 방어선이다.
     */
    fun sanitized(): GestureSettings = GestureSettings(
        moveSensitivity = coerceMoveSensitivity(moveSensitivity),
        scrollPxPerStep = coerceScrollPxPerStep(scrollPxPerStep),
    )

    companion object {
        /** [GestureConfig] 상수만으로 이루어진 기본 설정. "기본값으로 복원"의 목표 상태이기도 하다. */
        val DEFAULT = GestureSettings()

        /** [GestureSettings.moveSensitivity]의 허용 범위 — 슬라이더의 `valueRange`로 그대로 쓴다. */
        val MOVE_SENSITIVITY_RANGE: ClosedFloatingPointRange<Float> =
            GestureConfig.MOVE_SENSITIVITY_MIN..GestureConfig.MOVE_SENSITIVITY_MAX

        /** [GestureSettings.scrollPxPerStep]의 허용 범위. 하한이 `TAP_MAX_DISTANCE_PX`보다 크다는 것이 불변식이다. */
        val SCROLL_PX_PER_STEP_RANGE: ClosedFloatingPointRange<Float> =
            GestureConfig.SCROLL_PX_PER_STEP_MIN..GestureConfig.SCROLL_PX_PER_STEP_MAX

        /**
         * 범위 밖 값은 가까운 경계로, 유한하지 않은 값(NaN/±Infinity)은 기본값으로 바꾼다.
         *
         * NaN을 따로 처리하는 이유: [Float.coerceIn]은 비교 연산으로 구현돼 있는데 NaN은 어떤
         * 비교에도 false를 돌려주므로 clamp를 **그대로 통과한다**. NaN 배율이 판정기에 들어가면
         * 모든 델타가 NaN이 되어 커서가 영원히 멈춘다. ±Infinity는 경계로 clamp할 수도 있지만,
         * 저장된 값이 그 지경이면 이미 정상적인 경로로 쓰인 값이 아니므로 기본값으로 되돌린다.
         */
        fun coerceMoveSensitivity(value: Float): Float =
            if (!value.isFinite()) GestureConfig.MOVE_SENSITIVITY
            else value.coerceIn(MOVE_SENSITIVITY_RANGE)

        /** [coerceMoveSensitivity]와 같은 규칙을 [GestureSettings.scrollPxPerStep]에 적용한다. */
        fun coerceScrollPxPerStep(value: Float): Float =
            if (!value.isFinite()) GestureConfig.SCROLL_SENSITIVITY_PX_PER_STEP
            else value.coerceIn(SCROLL_PX_PER_STEP_RANGE)
    }
}
