package com.example.phone_pad_app.presentation.trackpad

import com.example.phone_pad_app.domain.model.GestureSettings
import com.example.phone_pad_app.presentation.util.GestureConfig
import kotlin.math.abs
import kotlin.math.hypot

/** 트래커가 방출하기로 결정한 커서 이동 델타 (이미 이동 감도 배율이 곱해진 값). */
data class MoveDelta(val dx: Float, val dy: Float)

/**
 * 트래커가 방출하기로 결정한 휠 스크롤 델타.
 *
 * 단위는 픽셀이 아니라 **정수 스텝(휠 노치 개수)** — `{"type":"SCROLL","dx":0,"dy":-3}`의
 * `dx`/`dy`와 같은 값이다 (AGENTS.md 섹션 4). 둘 다 0인 [ScrollDelta]는 만들지 않는다
 * (서버가 불필요한 SendInput을 호출하지 않도록).
 *
 * 부호: [dy] 양수 = 손가락이 아래로, [dx] 양수 = 손가락이 오른쪽으로 이동.
 */
data class ScrollDelta(val dx: Int, val dy: Int)

/**
 * 포인터 이벤트 한 건에 대한 판정 결과.
 *
 * @param move null이 아니면 그대로 `onMove(dx, dy)`를 호출하고 포인터 변화를 consume 한다.
 * @param scroll null이 아니면 그대로 `onScroll(dx, dy)`를 호출한다. [move]와 동시에 non-null이 되는
 *        일은 없다 — MOVE는 1손가락 구간, SCROLL은 2손가락 구간 전용이기 때문이다.
 * @param desktopSwitch null이 아니면 그대로 `onDesktopSwitch(direction)`를 호출한다. 값은
 *        `{"type":"DESKTOP_SWITCH","direction":...}`의 `direction` 필드와 같은 어휘
 *        ([MultiTouchGestureTracker.DIRECTION_LEFT] / [MultiTouchGestureTracker.DIRECTION_RIGHT])이며
 *        **전환 결과의 방향**이다(손가락 방향이 아니다 — 뒤집기는 트래커 안에서 끝난다).
 *        [move]/[scroll]과 동시에 non-null이 되는 일은 없다 — 3손가락이 한 번이라도 눌리면
 *        그 제스처의 MOVE/SCROLL이 전부 억제되기 때문이다(아래 3손가락 래치).
 * @param segmentStarted 이 이벤트에서 새 구간이 시작됐는지 (최초 down 또는 손가락 개수 변화에 의한 재시작).
 * @param pointerCount 이 이벤트 시점에 눌려 있던 포인터 개수.
 */
data class GestureDecision(
    val move: MoveDelta? = null,
    val scroll: ScrollDelta? = null,
    val desktopSwitch: String? = null,
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
 * @param x 탭으로 판정된 위치의 X (판정 근거가 된 구간의 시작 좌표). 더블탭 판정([DoubleTapDetector])이
 *        두 탭 사이 거리를 재는 데 쓴다. [clickButton]이 null이면 의미 없는 값이다.
 * @param y 탭으로 판정된 위치의 Y.
 */
data class GestureEndDecision(
    val clickButton: String? = null,
    val pointerCount: Int = 0,
    val x: Float = 0f,
    val y: Float = 0f,
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
 * - MOVE는 1손가락 구간에서만 방출한다.
 * - SCROLL은 2손가락 구간에서, 그 구간이 `isDrag`(누적 이동이 [GestureConfig.TAP_MAX_DISTANCE_PX] 초과)가
 *   된 이후에만 방출한다.
 * - DESKTOP_SWITCH는 **정확히 [GestureConfig.THREE_POINTER_COUNT]손가락인 구간**에서, 구간 시작
 *   centroid 대비 수평 이동이 [GestureConfig.THREE_FINGER_SWIPE_MIN_DISTANCE_PX] 이상이고
 *   [GestureConfig.THREE_FINGER_SWIPE_HORIZONTAL_DOMINANCE]배 수평 우세일 때 **임계를 넘는 그
 *   프레임에 즉시, 구간당 한 번만** 방출한다(계속 밀어도 반복 전환하지 않는다).
 * - **3손가락 래치:** 한 제스처(첫 down ~ 모든 손가락 up) 안에서 포인터가 한 번이라도
 *   [GestureConfig.THREE_POINTER_COUNT]개 이상이 되면, 그 제스처의 나머지 동안 MOVE·SCROLL·클릭을
 *   전부 억제하고 DESKTOP_SWITCH만 허용한다. 3손가락을 어긋나게 떼면 `3→2→1→0` 꼬리가 생기는데,
 *   그 꼬리가 [GestureConfig.MULTI_TOUCH_RELEASE_GRACE_MS] 밖이면 "정상 탭"으로 보여 스와이프
 *   직후에 좌/우클릭이 새기 때문이다. 유예 시간 튜닝이 아니라 제스처 단위 래치로 원천 차단한다.
 * - 제스처 종료 시 탭 분류는 마지막 구간의 손가락 개수를 기준으로 한다:
 *   1손가락 탭 → 좌클릭, 2손가락 탭 → 우클릭, 그 외 → 클릭 없음.
 * - 단, 마지막 구간이 **포인터 개수 감소**로 시작됐고 [GestureConfig.MULTI_TOUCH_RELEASE_GRACE_MS]
 *   안에 끝났다면 손가락을 어긋나게 뗀 꼬리로 보고 직전 구간 기준으로 판정한다.
 *
 * 스레드 안전하지 않다 — 하나의 포인터 입력 루프에서만 사용한다.
 *
 * @param moveSensitivity 커서 이동 배율. 사용자 설정([GestureSettings.moveSensitivity])이 있으면 그 값을,
 *        없으면 기본값 [GestureConfig.MOVE_SENSITIVITY]를 쓴다. 트래커는 설정이 **어디서 왔는지**
 *        (DataStore/Flow 등)는 전혀 모른다 — 값만 받는 순수 클래스로 남는다.
 * @param scrollPxPerStep 휠 1스텝에 해당하는 centroid 이동 거리 (px). 작을수록 스크롤이 빠르다.
 *        나눗수이므로 반드시 양수여야 한다(호출부는 [GestureSettings.sanitized]를 통과한 값을 넘긴다).
 */
class MultiTouchGestureTracker(
    private val moveSensitivity: Float = GestureConfig.MOVE_SENSITIVITY,
    private val scrollPxPerStep: Float = GestureConfig.SCROLL_SENSITIVITY_PX_PER_STEP,
) {

    /** 사용자 설정을 그대로 받는 편의 생성자 — 호출부가 필드를 하나씩 풀지 않아도 된다. */
    constructor(settings: GestureSettings) : this(
        moveSensitivity = settings.moveSensitivity,
        scrollPxPerStep = settings.scrollPxPerStep,
    )

    init {
        // 0이면 스크롤 스텝 계산이 0으로 나누기가 되어 Infinity 스텝이 나간다.
        require(scrollPxPerStep > 0f) {
            "scrollPxPerStep must be positive but was $scrollPxPerStep"
        }
    }

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

    /** 현재 구간에서 탭 최대 이동 거리를 넘겼는지. 2손가락 구간에서는 스크롤 시작 조건이기도 하다. */
    private var isDrag = false

    /**
     * 아직 정수 스텝으로 방출되지 못한 스크롤 잔차 (스텝 단위, |값| < 1).
     *
     * 픽셀→스텝 변환에서 남는 소수부를 버리지 않고 **구간이 끝날 때까지** 들고 있다가 다음 프레임에
     * 더한다. 이게 없으면 손가락을 천천히 움직일 때(프레임당 이동 < 1스텝) 모든 프레임이 0스텝으로
     * 잘려나가 스크롤이 아예 먹지 않는다. 구간이 새로 시작되면 0으로 리셋한다 — 다른 손가락 개수의
     * 이전 제스처에서 남은 잔차가 새 스크롤의 첫 스텝을 앞당기면 안 되기 때문이다.
     */
    private var scrollRemainderX = 0f
    private var scrollRemainderY = 0f

    /** 아직 한 번도 구간이 시작되지 않았으면 false. */
    private var hasSegment = false

    /**
     * 이 **구간**에서 이미 DESKTOP_SWITCH를 방출했는지.
     *
     * 한 구간(3손가락을 유지하는 동안)에 최대 1회 — 임계를 넘긴 뒤 계속 밀어도 데스크톱이
     * 계속 넘어가면 안 된다. 구간이 새로 시작되면(3→2→3 등) 다시 1회 가능하다.
     */
    private var desktopSwitchEmittedInSegment = false

    /**
     * 이 **제스처**에서 포인터가 한 번이라도 3개 이상이었는지 (3손가락 래치).
     *
     * 구간이 아니라 제스처 단위다 — 3손가락을 어긋나게 떼며 생기는 `3→2→1→0` 꼬리를
     * 통째로 무력화하는 것이 목적이라, 구간 단위였다면 꼬리마다 다시 풀려 의미가 없다.
     * [reset]/[onGestureEnd] 전까지 유지된다.
     */
    private var threeFingerLatched = false

    /**
     * 직전 구간의 요약 — 손가락을 어긋나게 떼서 생기는 짧은 꼬리 구간을 보정할 때만 사용한다.
     * ([GestureConfig.MULTI_TOUCH_RELEASE_GRACE_MS] 참조)
     */
    private var hasPrevSegment = false
    private var prevPointerCount = 0
    private var prevStartTimeMs = 0L
    private var prevIsDrag = false
    private var prevStartX = 0f
    private var prevStartY = 0f

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

        // 3손가락 래치: 한 번 걸리면 이 제스처가 끝날 때까지 풀리지 않는다.
        if (pointerCount >= GestureConfig.THREE_POINTER_COUNT) {
            threeFingerLatched = true
        }

        // 최초 시작이거나 손가락 개수가 바뀌었으면 진행 중이던 분류를 버리고 새 구간을 시작한다.
        if (!hasSegment || pointerCount != segmentPointerCount) {
            startSegment(pointerCount, x, y, timestampMs)
            return GestureDecision(
                move = null,
                scroll = null,
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
        // 래치가 걸렸으면(이 제스처에 3손가락이 있었으면) 1손가락으로 줄어든 꼬리에서도 방출하지 않는다.
        val move = if (
            !threeFingerLatched &&
            pointerCount == GestureConfig.SINGLE_POINTER_COUNT &&
            totalMoved > GestureConfig.MOVE_MIN_DISTANCE_PX
        ) {
            MoveDelta(
                dx = dx * moveSensitivity,
                dy = dy * moveSensitivity,
            )
        } else {
            null
        }

        // 2손가락 구간이 드래그로 확정된 뒤부터 centroid 이동을 휠 스텝으로 방출한다.
        // isDrag를 기준으로 삼으므로 2손가락 탭(우클릭, isDrag=false로 끝남)과 상호 배타적이다.
        val scroll = if (
            !threeFingerLatched &&
            pointerCount == GestureConfig.DOUBLE_POINTER_COUNT &&
            isDrag
        ) {
            accumulateScroll(dx, dy)
        } else {
            null
        }

        // 정확히 3손가락인 구간에서만 데스크톱 전환을 판정한다 (4손가락은 범위 밖 —
        // 래치 때문에 아무 이벤트도 나가지 않는다).
        val desktopSwitch = if (pointerCount == GestureConfig.THREE_POINTER_COUNT) {
            resolveDesktopSwitch(x, y)
        } else {
            null
        }

        return GestureDecision(
            move = move,
            scroll = scroll,
            desktopSwitch = desktopSwitch,
            pointerCount = pointerCount,
        )
    }

    /**
     * 구간 시작 centroid 대비 수평 이동이 임계를 넘겼으면 **전환 결과의 방향**을 돌려준다.
     * 이미 이 구간에서 한 번 방출했거나, 거리가 모자라거나, 수평 우세가 아니면 null.
     *
     * 방향 뒤집기가 여기서 끝난다 (Windows 정밀 터치패드 관례):
     * 손가락이 왼쪽으로 가면 콘텐츠가 밀려나며 **오른쪽** 데스크톱이 드러난다.
     * 서버는 이 매핑을 모르고 받은 값을 키 조합으로 옮기기만 한다 — 양쪽이 각자 뒤집으면
     * 원위치가 되므로 매핑 지점은 이 한 곳뿐이다.
     */
    private fun resolveDesktopSwitch(x: Float, y: Float): String? {
        if (desktopSwitchEmittedInSegment) return null

        val totalDx = x - segmentStartX
        val totalDy = y - segmentStartY
        val horizontal = abs(totalDx)
        if (horizontal < GestureConfig.THREE_FINGER_SWIPE_MIN_DISTANCE_PX) return null
        // 대각선·수직 스와이프는 좌/우 전환으로 해석하지 않는다.
        if (horizontal < GestureConfig.THREE_FINGER_SWIPE_HORIZONTAL_DOMINANCE * abs(totalDy)) {
            return null
        }

        desktopSwitchEmittedInSegment = true
        return if (totalDx < 0f) DIRECTION_RIGHT else DIRECTION_LEFT
    }

    /**
     * 픽셀 델타를 정수 스텝으로 바꾸고, 소수부 잔차는 구간 상태에 남겨 다음 프레임으로 넘긴다.
     * 방출할 스텝이 하나도 없으면 null을 돌려준다.
     */
    private fun accumulateScroll(dx: Float, dy: Float): ScrollDelta? {
        scrollRemainderX += dx / scrollPxPerStep
        scrollRemainderY += dy / scrollPxPerStep

        // toInt()는 0 방향으로 버리므로 음수 잔차도 대칭으로 처리된다 (-1.7 → -1, 잔차 -0.7).
        val stepsX = scrollRemainderX.toInt()
        val stepsY = scrollRemainderY.toInt()
        scrollRemainderX -= stepsX
        scrollRemainderY -= stepsY

        return if (stepsX == 0 && stepsY == 0) null else ScrollDelta(dx = stepsX, dy = stepsY)
    }

    /**
     * 모든 손가락이 떨어져 제스처가 완전히 끝났을 때 호출한다.
     * 판정은 **마지막 구간**의 손가락 개수/시작 시각/드래그 여부를 기준으로 한다.
     * 호출 후 트래커는 초기 상태로 돌아간다.
     */
    fun onGestureEnd(timestampMs: Long): GestureEndDecision {
        val lastPointerCount = if (hasSegment) segmentPointerCount else 0
        val tap = resolveTap(timestampMs, lastPointerCount)

        reset()
        return GestureEndDecision(
            clickButton = tap.button,
            pointerCount = lastPointerCount,
            x = tap.x,
            y = tap.y,
        )
    }

    /** [resolveTap]의 결과 — 버튼과, 그 판정 근거가 된 구간의 시작 좌표. */
    private data class TapResolution(val button: String?, val x: Float, val y: Float)

    /**
     * 종료 시각 기준으로 어떤 버튼의 탭이었는지, 그리고 그 탭의 위치가 어디인지 판정한다.
     * 탭이 아니면 [TapResolution.button]이 null.
     *
     * 마지막 구간이 "손가락을 어긋나게 뗀 꼬리"(개수 감소로 시작 + 이동 없음 + 유예 시간 내 종료)면
     * 그 꼬리를 버리고 직전 구간(더 많은 손가락)을 제스처의 실체로 보고 판정한다 — 좌표도
     * 같은 기준을 따라 그 구간의 시작 좌표를 쓴다.
     */
    private fun resolveTap(timestampMs: Long, lastPointerCount: Int): TapResolution {
        if (!hasSegment) return TapResolution(null, 0f, 0f)

        // 3손가락 래치: 이 제스처에 3손가락이 한 번이라도 있었다면 어떤 꼬리도 탭이 아니다.
        // (3손가락 "탭" 자체도 클릭이 아니므로, 스와이프 유무와 무관하게 무이벤트가 맞다.)
        if (threeFingerLatched) return TapResolution(null, segmentStartX, segmentStartY)

        // F-2: 직전 구간이 이미 드래그/스크롤이었다면, 손가락을 마저 떼는 짧은 꼬리는
        // 새 탭으로 재해석하지 않는다 — 유예 시간 안에 끝났는지와 무관하게 무조건 무시.
        // 이게 없으면 2손가락 스크롤 직후 손가락을 어긋나게 떼기만 해도(50~200ms 사이)
        // 좌클릭이 튀어나온다("스크롤하고 손 뗐을 뿐인데 링크가 클릭됨").
        if (hasPrevSegment && prevIsDrag) return TapResolution(null, segmentStartX, segmentStartY)

        val elapsed = timestampMs - segmentStartTimeMs
        val isReleaseTail = hasPrevSegment &&
            prevPointerCount > lastPointerCount &&
            !isDrag &&
            elapsed < GestureConfig.MULTI_TOUCH_RELEASE_GRACE_MS

        return if (isReleaseTail) {
            TapResolution(
                button = buttonForTap(
                    pointerCount = prevPointerCount,
                    drag = prevIsDrag,
                    elapsed = timestampMs - prevStartTimeMs,
                ),
                x = prevStartX,
                y = prevStartY,
            )
        } else {
            TapResolution(
                button = buttonForTap(
                    pointerCount = lastPointerCount,
                    drag = isDrag,
                    elapsed = elapsed,
                ),
                x = segmentStartX,
                y = segmentStartY,
            )
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
        scrollRemainderX = 0f
        scrollRemainderY = 0f
        desktopSwitchEmittedInSegment = false
        threeFingerLatched = false
        hasPrevSegment = false
        prevPointerCount = 0
        prevStartTimeMs = 0L
        prevIsDrag = false
        prevStartX = 0f
        prevStartY = 0f
    }

    private fun startSegment(pointerCount: Int, x: Float, y: Float, timestampMs: Long) {
        if (hasSegment) {
            hasPrevSegment = true
            prevPointerCount = segmentPointerCount
            prevStartTimeMs = segmentStartTimeMs
            prevIsDrag = isDrag
            prevStartX = segmentStartX
            prevStartY = segmentStartY
        }
        hasSegment = true
        segmentPointerCount = pointerCount
        segmentStartX = x
        segmentStartY = y
        segmentStartTimeMs = timestampMs
        lastX = x
        lastY = y
        isDrag = false
        scrollRemainderX = 0f
        scrollRemainderY = 0f
        // 구간 단위 — 3→2→3처럼 구간이 새로 시작되면 다시 한 번 전환할 수 있다.
        // (제스처 단위인 threeFingerLatched는 여기서 건드리지 않는다.)
        desktopSwitchEmittedInSegment = false
    }

    companion object {
        /** `{"type":"CLICK","button":"left"}` (AGENTS.md 섹션 4) */
        const val BUTTON_LEFT = "left"

        /** `{"type":"CLICK","button":"right"}` (AGENTS.md 섹션 4) */
        const val BUTTON_RIGHT = "right"

        /**
         * `{"type":"DESKTOP_SWITCH","direction":"left"}` — **왼쪽 데스크톱으로 전환**
         * (서버: `Ctrl+Win+Left`). 손가락은 **오른쪽**으로 스와이프한 경우다.
         */
        const val DIRECTION_LEFT = "left"

        /**
         * `{"type":"DESKTOP_SWITCH","direction":"right"}` — **오른쪽 데스크톱으로 전환**
         * (서버: `Ctrl+Win+Right`). 손가락은 **왼쪽**으로 스와이프한 경우다.
         */
        const val DIRECTION_RIGHT = "right"
    }
}
