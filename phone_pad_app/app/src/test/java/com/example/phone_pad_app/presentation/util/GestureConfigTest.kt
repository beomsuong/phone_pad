package com.example.phone_pad_app.presentation.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 채널 포트와 제스처 임계값은 코드 곳곳에 하드코딩되지 않고 여기서만 정의된다.
 * 값이 바뀌면 서버(pc_server) 및 AGENTS.md 섹션 4와 함께 갱신해야 하므로 회귀 테스트로 고정한다.
 */
class GestureConfigTest {

    @Test
    fun `TCP는 9000 UDP는 9001이며 서로 다른 포트다`() {
        assertEquals(9000, GestureConfig.DEFAULT_PORT)
        assertEquals(9001, GestureConfig.UDP_PORT)
        assertTrue(GestureConfig.DEFAULT_PORT != GestureConfig.UDP_PORT)
    }

    @Test
    fun `세션 핸드셰이크 타임아웃은 양수다`() {
        assertTrue(GestureConfig.SESSION_HANDSHAKE_TIMEOUT_MS > 0)
    }

    @Test
    fun `heartbeat 상수는 확정 스펙 값과 일치한다`() {
        // 서버(pc_server) HEARTBEAT_INTERVAL_S = 5.0 / HEARTBEAT_MISS_LIMIT = 3 과 대칭
        assertEquals(5000L, GestureConfig.HEARTBEAT_INTERVAL_MS)
        assertEquals(3, GestureConfig.HEARTBEAT_MISS_LIMIT)
        // 5초 × 3회 ≈ 15초 (AGENTS.md 섹션 6)
        assertEquals(
            15_000L,
            GestureConfig.HEARTBEAT_INTERVAL_MS * GestureConfig.HEARTBEAT_MISS_LIMIT
        )
        // 소켓 soTimeout(Int)으로 그대로 쓸 수 있는 범위여야 한다
        assertTrue(GestureConfig.HEARTBEAT_INTERVAL_MS <= Int.MAX_VALUE.toLong())
    }

    @Test
    fun `제스처 임계값은 MOVE 최소 거리가 탭 최대 거리보다 작다`() {
        assertTrue(GestureConfig.MOVE_MIN_DISTANCE_PX < GestureConfig.TAP_MAX_DISTANCE_PX)
        assertTrue(GestureConfig.MOVE_SENSITIVITY > 0f)
        assertTrue(GestureConfig.TAP_MAX_DURATION_MS > 0L)
    }

    @Test
    fun `단일 포인터 구간 기준은 1손가락이다`() {
        // MultiTouchGestureTracker가 MOVE/CLICK을 방출하는 유일한 구간 조건
        assertEquals(1, GestureConfig.SINGLE_POINTER_COUNT)
    }

    @Test
    fun `2손가락 구간 기준은 2손가락이며 단일 포인터와 다르다`() {
        // 우클릭(CLICK button="right") 판정의 유일한 구간 조건 (AGENTS.md 섹션 5)
        assertEquals(2, GestureConfig.DOUBLE_POINTER_COUNT)
        assertTrue(GestureConfig.DOUBLE_POINTER_COUNT > GestureConfig.SINGLE_POINTER_COUNT)
    }

    @Test
    fun `스크롤 1스텝 거리는 탭 최대 이동 거리보다 크다`() {
        // 스크롤은 isDrag(= 탭 최대 이동 거리 초과) 이후에만 시작된다. 스텝 단위가 그보다 작으면
        // 스크롤이 걸리는 순간 이미 1스텝 이상이 쌓여 있어 첫 프레임에 툭 튄다.
        assertTrue(GestureConfig.SCROLL_SENSITIVITY_PX_PER_STEP > 0f)
        assertTrue(
            GestureConfig.SCROLL_SENSITIVITY_PX_PER_STEP > GestureConfig.TAP_MAX_DISTANCE_PX
        )
    }

    @Test
    fun `사용자 조정 범위는 기본값을 포함한다`() {
        // 기본값이 범위 밖이면 설정 화면을 한 번 열기만 해도 값이 경계로 끌려가 동작이 바뀐다.
        assertTrue(GestureConfig.MOVE_SENSITIVITY_MIN < GestureConfig.MOVE_SENSITIVITY_MAX)
        assertTrue(GestureConfig.MOVE_SENSITIVITY >= GestureConfig.MOVE_SENSITIVITY_MIN)
        assertTrue(GestureConfig.MOVE_SENSITIVITY <= GestureConfig.MOVE_SENSITIVITY_MAX)

        assertTrue(GestureConfig.SCROLL_PX_PER_STEP_MIN < GestureConfig.SCROLL_PX_PER_STEP_MAX)
        assertTrue(GestureConfig.SCROLL_SENSITIVITY_PX_PER_STEP >= GestureConfig.SCROLL_PX_PER_STEP_MIN)
        assertTrue(GestureConfig.SCROLL_SENSITIVITY_PX_PER_STEP <= GestureConfig.SCROLL_PX_PER_STEP_MAX)
    }

    @Test
    fun `이동 감도 하한은 0보다 크다`() {
        // 0이면 커서가 전혀 움직이지 않아 "고장난 앱"이 된다 — 슬라이더로도 도달 불가여야 한다.
        assertTrue(GestureConfig.MOVE_SENSITIVITY_MIN > 0f)
    }

    @Test
    fun `스크롤 스텝 거리 하한도 탭 최대 이동 거리보다 크다`() {
        // 위 `스크롤 1스텝 거리는...` 테스트와 같은 불변식을 **사용자가 도달 가능한 최솟값**에
        // 대해서도 강제한다. 하한을 낮추는 순간 슬라이더를 끝까지 내린 사용자만 스크롤 진입이
        // 튀는, 재현하기 까다로운 버그가 된다.
        assertTrue(
            GestureConfig.SCROLL_PX_PER_STEP_MIN > GestureConfig.TAP_MAX_DISTANCE_PX
        )
    }

    @Test
    fun `더블탭 상수는 확정 스펙 값과 일치한다`() {
        assertEquals(300L, GestureConfig.DOUBLE_TAP_INTERVAL_MS)
        assertEquals(40f, GestureConfig.DOUBLE_TAP_DISTANCE_PX, 0.001f)
    }

    @Test
    fun `더블탭 허용 거리는 탭 최대 이동 거리보다 크다`() {
        // 사람이 같은 자리를 두 번 탭해도 몇 px씩 어긋난다. "탭 하나로 인정되는 이동 거리"보다
        // 넉넉해야 두 번째 탭이 더블탭으로 묶인다 (확정 스펙: TAP_MAX_DISTANCE_PX의 2배).
        assertTrue(
            GestureConfig.DOUBLE_TAP_DISTANCE_PX > GestureConfig.TAP_MAX_DISTANCE_PX
        )
        assertEquals(
            GestureConfig.TAP_MAX_DISTANCE_PX * 2f,
            GestureConfig.DOUBLE_TAP_DISTANCE_PX,
            0.001f,
        )
    }

    @Test
    fun `더블탭 간격은 탭 최대 지속 시간보다 길다`() {
        // 두 번째 탭은 "탭으로 끝난 뒤"에야 판정에 들어온다. 간격이 탭 지속 시간보다 짧으면
        // 정상 속도로 두 번 탭해도 두 번째 탭이 시작되기 전에 지연 클릭이 먼저 나가버린다.
        assertTrue(
            GestureConfig.DOUBLE_TAP_INTERVAL_MS > GestureConfig.TAP_MAX_DURATION_MS
        )
    }

    @Test
    fun `드래그 홀드 임계는 탭 최대 지속 시간과 정확히 같다`() {
        // 확정 스펙: "탭으로 인정되지 않게 되는 시점" == "드래그 홀드가 되는 시점".
        // 두 값이 어긋나면 탭도 드래그도 아닌 사각지대(또는 둘 다 발사되는 구간)가 생긴다.
        assertEquals(GestureConfig.TAP_MAX_DURATION_MS, GestureConfig.DRAG_HOLD_THRESHOLD_MS)
        assertEquals(200L, GestureConfig.DRAG_HOLD_THRESHOLD_MS)
    }

    @Test
    fun `드래그 홀드 임계는 멀티터치 해제 유예보다 길다`() {
        // 그렇지 않으면 손가락을 어긋나게 떼는 꼬리 구간이 드래그 홀드로 승격될 수 있다.
        assertTrue(
            GestureConfig.DRAG_HOLD_THRESHOLD_MS > GestureConfig.MULTI_TOUCH_RELEASE_GRACE_MS
        )
    }

    @Test
    fun `드래그 홀드 임계는 더블탭 간격보다 짧다`() {
        // 홀드로 승격되는 순간 대기 중이던 지연 클릭을 flush 해야 하는데, 임계가 더블탭 간격보다
        // 길면 그 클릭이 이미 스스로 나간 뒤라 flush 의미가 없어진다(= 드래그 도중 클릭이 도착).
        assertTrue(
            GestureConfig.DRAG_HOLD_THRESHOLD_MS < GestureConfig.DOUBLE_TAP_INTERVAL_MS
        )
    }

    @Test
    fun `멀티터치 해제 유예는 탭 최대 지속 시간의 절반보다 짧다`() {
        // 이 여유가 없으면 "손가락 하나를 떼고 남은 손가락으로 탭"하는 동작까지
        // 2손가락 탭으로 삼켜버린다 (MultiTouchGestureTracker의 꼬리 보정 전제 조건)
        assertTrue(GestureConfig.MULTI_TOUCH_RELEASE_GRACE_MS > 0L)
        assertTrue(
            GestureConfig.MULTI_TOUCH_RELEASE_GRACE_MS < GestureConfig.TAP_MAX_DURATION_MS / 2
        )
    }
}
