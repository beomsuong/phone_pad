package com.example.phone_pad_app.presentation.trackpad

import com.example.phone_pad_app.presentation.util.GestureConfig
import kotlin.math.hypot

/** 트래커가 방출하기로 결정한 커서 이동 델타 (이미 [GestureConfig.MOVE_SENSITIVITY]가 곱해진 값). */
data class MoveDelta(val dx: Float, val dy: Float)

/**
 * 포인터 이벤트 한 건에 대한 판정 결과.
 *
 * @param move null이 아니면 그대로 `onMove(dx, dy)`를 호출하고 포인터 변화를 consume 한다.
 * @param segmentStarted 이 이벤트에서 새 구간이 시작됐는지 (최초 down 또는 손가락 개수 변화에 의한 재시작).
 * @param pointerCount 이 이벤트 시점에 눌려 있던 포인터 개수.
 */
data class GestureDecision(
    val move: MoveDelta? = null,
    val segmentStarted: Boolean = false,
    val pointerCount: Int = 0,
)

/**
 * 제스처 전체가 끝났을 때(모든 손가락이 떨어졌을 때)의 판정 결과.
 *
 * @param clickButton 탭으로 판정됐다면 눌러야 할 마우스 버튼
 *        ([MultiTouchGestureTracker.BUTTON_LEFT] / [MultiTouchGestureTracker.BUTTON_RIGHT]),
 *        탭이 아니면 null. 값은 `{"type":"CLICK","button":...}`의 `button` 필드와 같은 어휘다.
 * @param pointerCount 마지막 구간의 손가락 개수.
 */
data class GestureEndDecision(
    val clickButton: String? = null,
    val pointerCount: Int = 0,
)

/**
 * 멀티터치 제스처 판정기.
 *
 * Compose의 `PointerInputScope`/`awaitPointerEvent()`에 전혀 의존하지 않는 순수 Kotlin 클래스라서
 * JUnit 단위 테스트로 검증할 수 있다. `TrackpadScreen`은 매 이벤트의
 * "눌린 포인터 개수 + 대표 좌표(눌린 포인터들의 중심점) + 타임스탬프"를 넘기고,
 * 반환된 [GestureDecision]/[GestureEndDecision]대로 `onMove`/`onClick`을 호출하는 얇은 어댑터다.
 *
 * 판정 규칙 (AGENTS.md 섹션 5):
 * - 손가락 개수가 바뀌면 진행 중이던 구간을 취소하고 그 시점 좌표/시각으로 새 구간을 시작한다.
 * - MOVE는 1손가락 구간에서만 방출한다. 2손가락 이상 구간은 내부 추적만 한다 (스크롤 연결은 후속 작업).
 * - 제스처 종료 시 탭 분류는 마지막 구간의 손가락 개수를 기준으로 한다:
 *   1손가락 탭 → 좌클릭, 2손가락 탭 → 우클릭, 그 외 → 클릭 없음.
 * - 단, 마지막 구간이 **포인터 개수 감소**로 시작됐고 [GestureConfig.MULTI_TOUCH_RELEASE_GRACE_MS]
 *   안에 끝났다면 손가락을 어긋나게 뗀 꼬리로 보고 직전 구간 기준으로 판정한다.
 *
 * 스레드 안전하지 않다 — 하나의 포인터 입력 루프에서만 사용한다.
 */
class MultiTouchGestureTracker {

    /** 현재 구간의 손가락 개수. 구간이 없으면 0. */
    private var segmentPointerCount = 0

    /** 현재 구간의 시작 좌표 — 탭/드래그 판정용 누적 이동 거리의 기준점. */
    private var segmentStartX = 0f
    private var segmentStartY = 0f

    /** 현재 구간의 시작 시각 — 탭 지속 시간 판정의 기준. */
    private var segmentStartTimeMs = 0L

    /** 직전 이벤트 좌표 — 프레임 간 델타 계산용. */
    private var lastX = 0f
    private var lastY = 0f

    /** 현재 구간에서 탭 최대 이동 거리를 넘겼는지. */
    private var isDrag = false

    /** 아직 한 번도 구간이 시작되지 않았으면 false. */
    private var hasSegment = false

    /**
     * 직전 구간의 요약 — 손가락을 어긋나게 떼서 생기는 짧은 꼬리 구간을 보정할 때만 사용한다.
     * ([GestureConfig.MULTI_TOUCH_RELEASE_GRACE_MS] 참조)
     */
    private var hasPrevSegment = false
    private var prevPointerCount = 0
    private var prevStartTimeMs = 0L
    private var prevIsDrag = false

    /**
     * 포인터 이벤트 한 건을 처리한다.
     *
     * @param pointerCount 이 시점에 눌려 있는(pressed) 포인터 개수. 0 이하면 아무것도 하지 않는다
     *        (제스처 종료는 [onGestureEnd]로 알린다).
     * @param x 눌린 포인터들의 중심 X (1손가락이면 그 포인터의 좌표와 동일).
     * @param y 눌린 포인터들의 중심 Y.
     * @param timestampMs 이벤트 시각 (ms).
     */
    fun onPointerEvent(
        pointerCount: Int,
        x: Float,
        y: Float,
        timestampMs: Long,
    ): GestureDecision {
        if (pointerCount <= 0) {
            return GestureDecision(pointerCount = 0)
        }

        // 최초 시작이거나 손가락 개수가 바뀌었으면 진행 중이던 분류를 버리고 새 구간을 시작한다.
        if (!hasSegment || pointerCount != segmentPointerCount) {
            startSegment(pointerCount, x, y, timestampMs)
            return GestureDecision(
                move = null,
                segmentStarted = true,
                pointerCount = pointerCount,
            )
        }

        val dx = x - lastX
        val dy = y - lastY
        lastX = x
        lastY = y

        if (dx == 0f && dy == 0f) {
            return GestureDecision(pointerCount = pointerCount)
        }

        val totalMoved = hypot(x - segmentStartX, y - segmentStartY)
        if (totalMoved > GestureConfig.TAP_MAX_DISTANCE_PX) {
            isDrag = true
        }

        // 1손가락 구간에서만 커서 이동을 방출한다. 멀티터치 구간은 추적만 한다.
        val move = if (
            pointerCount == GestureConfig.SINGLE_POINTER_COUNT &&
            totalMoved > GestureConfig.MOVE_MIN_DISTANCE_PX
        ) {
            MoveDelta(
                dx = dx * GestureConfig.MOVE_SENSITIVITY,
                dy = dy * GestureConfig.MOVE_SENSITIVITY,
            )
        } else {
            null
        }

        return GestureDecision(move = move, pointerCount = pointerCount)
    }

    /**
     * 모든 손가락이 떨어져 제스처가 완전히 끝났을 때 호출한다.
     * 판정은 **마지막 구간**의 손가락 개수/시작 시각/드래그 여부를 기준으로 한다.
     * 호출 후 트래커는 초기 상태로 돌아간다.
     */
    fun onGestureEnd(timestampMs: Long): GestureEndDecision {
        val lastPointerCount = if (hasSegment) segmentPointerCount else 0
        val clickButton = resolveClickButton(timestampMs, lastPointerCount)

        reset()
        return GestureEndDecision(clickButton = clickButton, pointerCount = lastPointerCount)
    }

    /**
     * 종료 시각 기준으로 어떤 버튼의 탭이었는지 판정한다. 탭이 아니면 null.
     *
     * 마지막 구간이 "손가락을 어긋나게 뗀 꼬리"(개수 감소로 시작 + 이동 없음 + 유예 시간 내 종료)면
     * 그 꼬리를 버리고 직전 구간(더 많은 손가락)을 제스처의 실체로 보고 판정한다.
     */
    private fun resolveClickButton(timestampMs: Long, lastPointerCount: Int): String? {
        if (!hasSegment) return null

        val elapsed = timestampMs - segmentStartTimeMs
        val isReleaseTail = hasPrevSegment &&
            prevPointerCount > lastPointerCount &&
            !isDrag &&
            elapsed < GestureConfig.MULTI_TOUCH_RELEASE_GRACE_MS

        return if (isReleaseTail) {
            buttonForTap(
                pointerCount = prevPointerCount,
                drag = prevIsDrag,
                elapsed = timestampMs - prevStartTimeMs,
            )
        } else {
            buttonForTap(pointerCount = lastPointerCount, drag = isDrag, elapsed = elapsed)
        }
    }

    /** 탭 조건(이동·시간)을 만족하면 손가락 개수에 대응하는 버튼을, 아니면 null을 돌려준다. */
    private fun buttonForTap(pointerCount: Int, drag: Boolean, elapsed: Long): String? {
        if (drag || elapsed >= GestureConfig.TAP_MAX_DURATION_MS) return null
        return when (pointerCount) {
            GestureConfig.SINGLE_POINTER_COUNT -> BUTTON_LEFT
            GestureConfig.DOUBLE_POINTER_COUNT -> BUTTON_RIGHT
            else -> null
        }
    }

    /** 진행 중이던 상태를 모두 버린다. */
    fun reset() {
        hasSegment = false
        segmentPointerCount = 0
        segmentStartX = 0f
        segmentStartY = 0f
        segmentStartTimeMs = 0L
        lastX = 0f
        lastY = 0f
        isDrag = false
        hasPrevSegment = false
        prevPointerCount = 0
        prevStartTimeMs = 0L
        prevIsDrag = false
    }

    private fun startSegment(pointerCount: Int, x: Float, y: Float, timestampMs: Long) {
        if (hasSegment) {
            hasPrevSegment = true
            prevPointerCount = segmentPointerCount
            prevStartTimeMs = segmentStartTimeMs
            prevIsDrag = isDrag
        }
        hasSegment = true
        segmentPointerCount = pointerCount
        segmentStartX = x
        segmentStartY = y
        segmentStartTimeMs = timestampMs
        lastX = x
        lastY = y
        isDrag = false
    }

    companion object {
        /** `{"type":"CLICK","button":"left"}` (AGENTS.md 섹션 4) */
        const val BUTTON_LEFT = "left"

        /** `{"type":"CLICK","button":"right"}` (AGENTS.md 섹션 4) */
        const val BUTTON_RIGHT = "right"
    }
}
