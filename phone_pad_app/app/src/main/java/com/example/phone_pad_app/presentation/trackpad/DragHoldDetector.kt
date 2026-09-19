package com.example.phone_pad_app.presentation.trackpad

import com.example.phone_pad_app.presentation.util.GestureConfig
import kotlin.math.hypot

/**
 * [DragHoldDetector]가 호출부에 돌려주는 신호.
 *
 * 그대로 `{"type":"DRAG_START"}` / `{"type":"DRAG_END"}` 전송에 대응한다 (AGENTS.md 섹션 4).
 * 한 번의 입력 처리에서 두 신호가 동시에 나오는 일은 없다 — 승격은 비활성 상태에서만,
 * 해제는 활성 상태에서만 일어나기 때문이다.
 */
enum class DragHoldSignal {
    /** 아무 일도 없음. */
    None,

    /** 이 순간 드래그 홀드로 승격됐다 → `DRAG_START` 전송. */
    Start,

    /** 활성 상태였던 드래그 홀드가 끝났다 → `DRAG_END` 전송. */
    End,
}

/**
 * "탭홀드 → 드래그" 승격 판정기 (AGENTS.md 섹션 5, Phase 3).
 *
 * [MultiTouchGestureTracker]/[DoubleTapDetector]와 같은 설계 원칙을 따르는 순수 Kotlin 클래스다 —
 * Compose의 `PointerInputScope`에도 코루틴에도 의존하지 않으므로 JUnit으로 전부 검증할 수 있다.
 * **시간을 흘려보내는 일(타이머 대기)은 이 클래스의 책임이 아니다**: 호출부(`TrackpadScreen`)가
 * [remainingHoldMs]만큼 `awaitPointerEvent()`와 타임아웃을 경합시키고, 타임아웃이 이기면
 * [onHoldTimeout]을, 포인터 이벤트가 이기면 [onPointerEvent]를 호출한다. 여기서는 오직
 * **"지금까지의 경과 시간과 이동 거리로 승격해야 하는가"** 만 판정한다.
 *
 * 판정 규칙:
 * - 1손가락으로 시작한 제스처만 후보다. 시작 좌표에서 [GestureConfig.TAP_MAX_DISTANCE_PX] 이내를
 *   유지한 채 [GestureConfig.DRAG_HOLD_THRESHOLD_MS]가 지나면 승격([DragHoldSignal.Start]).
 * - 승격 전에 그 거리를 한 번이라도 넘기면 이 제스처에서는 영영 승격하지 않는다(sticky) —
 *   되돌아와서 제자리를 지켜도 마찬가지다. [MultiTouchGestureTracker]의 `isDrag`가 sticky한 것과
 *   같은 규약이며, "빨리 움직이면 버튼 없는 일반 커서 이동"이라는 기존 동작을 그대로 지킨다.
 * - 승격 뒤의 이동은 이 클래스의 관심사가 아니다 — 기존 MOVE 경로가 그대로 처리한다.
 * - 손가락 개수가 1이 아니게 되면 즉시 해제([DragHoldSignal.End])하고, **그 제스처 안에서는
 *   다시 무장하지 않는다.** 2손가락 드래그 홀드가 범위 밖인 이유도 있지만, 더 중요한 이유는
 *   2손가락 스크롤 뒤 손가락을 하나씩 떼는 꼬리 구간이 "제자리 1손가락 유지"로 보여 드래그가
 *   오발동하는 것을 막기 위해서다 (AGENTS.md 섹션 5의 스크롤 뒤 클릭 오발동 방지와 같은 성격).
 *
 * 스레드 안전하지 않다 — 하나의 포인터 입력 루프에서만 사용한다.
 */
class DragHoldDetector {

    /** 승격 후보로 추적 중인지. 거리 초과·포인터 개수 변화·승격·종료로 해제된다. */
    private var armed = false

    /** 승격되어 `DRAG_START`를 이미 내보낸 상태인지 (= PC 왼쪽 버튼이 눌려 있는 상태). */
    private var active = false

    /** 이 제스처에서 한 번이라도 승격했는지. 제스처 종료 후에도 [reset] 전까지 유지된다. */
    private var promotedOnce = false

    /** 승격 판정의 기준점 — 제스처 시작 좌표/시각. */
    private var anchorX = 0f
    private var anchorY = 0f
    private var startTimeMs = 0L

    /** 현재 드래그 홀드가 활성(버튼이 눌린 상태)인지. */
    val isActive: Boolean get() = active

    /**
     * 이 제스처가 드래그 홀드로 승격된 적이 있는지.
     *
     * 호출부는 이 값이 true면 제스처 종료 시 탭/더블탭/클릭 판정을 **아예 하지 않는다**
     * (확정 스펙 7번: 드래그 홀드로 끝난 제스처는 `DRAG_END`만 내고 끝난다).
     */
    val hasPromoted: Boolean get() = promotedOnce

    /**
     * 제스처가 시작됐음을(첫 손가락이 닿았음을) 알리고 승격 후보로 무장한다.
     *
     * @param x 첫 down의 X
     * @param y 첫 down의 Y
     * @param timestampMs 첫 down 시각 (ms) — 홀드 시간의 기준점
     */
    fun onGestureStart(x: Float, y: Float, timestampMs: Long) {
        armed = true
        active = false
        promotedOnce = false
        anchorX = x
        anchorY = y
        startTimeMs = timestampMs
    }

    /**
     * 포인터 이벤트 한 건을 반영한다.
     *
     * 타이머가 도는 도중에도 미세한 떨림 때문에 이벤트가 들어올 수 있으므로, 여기서도
     * 승격 조건(시간 경과 + 제자리 유지)을 검사한다 — 타이머 경합만으로는 "이벤트가
     * 임계 시각 직후에 도착한" 경우를 놓칠 수 있기 때문이다.
     *
     * @param pointerCount 이 시점에 눌려 있는 포인터 개수
     * @param x 눌린 포인터들의 중심 X
     * @param y 눌린 포인터들의 중심 Y
     * @param timestampMs 이벤트 시각 (ms)
     */
    fun onPointerEvent(
        pointerCount: Int,
        x: Float,
        y: Float,
        timestampMs: Long,
    ): DragHoldSignal {
        if (pointerCount != GestureConfig.SINGLE_POINTER_COUNT) {
            // 확정 스펙 5번: 손가락 개수가 바뀌면 즉시 버튼을 놓는다.
            return release()
        }
        if (active || !armed) return DragHoldSignal.None

        if (hypot(x - anchorX, y - anchorY) > GestureConfig.TAP_MAX_DISTANCE_PX) {
            // 확정 스펙 3번: 시간이 되기 전에 움직였다 — 기존처럼 버튼 없는 커서 이동으로 남는다.
            armed = false
            return DragHoldSignal.None
        }
        return promoteIfDue(timestampMs)
    }

    /**
     * [remainingHoldMs]만큼 새 포인터 이벤트 없이 시간이 흘렀음을 알린다(= 제자리 유지).
     *
     * 이미 승격했거나 후보에서 탈락한 뒤라면 아무 일도 하지 않는다.
     */
    fun onHoldTimeout(timestampMs: Long): DragHoldSignal {
        if (active || !armed) return DragHoldSignal.None
        return promoteIfDue(timestampMs)
    }

    /**
     * 제스처가 끝났음을(모든 손가락이 떨어졌거나 제스처가 취소됐음을) 알린다.
     *
     * 활성 상태였다면 [DragHoldSignal.End]를 돌려준다. 여러 번 호출해도 안전하다 —
     * 두 번째부터는 [DragHoldSignal.None]이다. 호출부가 정상 종료 경로와 `finally`
     * (취소 경로) 양쪽에서 호출할 수 있게 하기 위한 멱등성이다.
     */
    fun onGestureEnd(): DragHoldSignal = release()

    /**
     * 승격 타이머의 남은 시간 (ms).
     *
     * @return 아직 승격 후보라면 남은 시간(이미 임계를 넘겼으면 0), 후보가 아니면(이미 승격했거나
     *         거리 초과/개수 변화로 탈락했거나 제스처가 끝났으면) null. null이면 호출부는
     *         타임아웃 없이 그냥 `awaitPointerEvent()`를 기다리면 된다.
     */
    fun remainingHoldMs(nowMs: Long): Long? {
        if (active || !armed) return null
        val remaining = GestureConfig.DRAG_HOLD_THRESHOLD_MS - (nowMs - startTimeMs)
        return if (remaining < 0L) 0L else remaining
    }

    /** 진행 중이던 상태를 모두 버린다 ([hasPromoted] 포함). */
    fun reset() {
        armed = false
        active = false
        promotedOnce = false
        anchorX = 0f
        anchorY = 0f
        startTimeMs = 0L
    }

    /** 경과 시간이 임계에 도달했으면 승격한다. 아직이면 아무 일도 없다. */
    private fun promoteIfDue(timestampMs: Long): DragHoldSignal {
        if (timestampMs - startTimeMs < GestureConfig.DRAG_HOLD_THRESHOLD_MS) {
            return DragHoldSignal.None
        }
        armed = false
        active = true
        promotedOnce = true
        return DragHoldSignal.Start
    }

    /** 무장/활성 상태를 내려놓는다. 활성이었다면 [DragHoldSignal.End]. */
    private fun release(): DragHoldSignal {
        val signal = if (active) DragHoldSignal.End else DragHoldSignal.None
        armed = false
        active = false
        return signal
    }
}
