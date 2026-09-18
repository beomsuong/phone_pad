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
    fun `멀티터치 해제 유예는 탭 최대 지속 시간의 절반보다 짧다`() {
        // 이 여유가 없으면 "손가락 하나를 떼고 남은 손가락으로 탭"하는 동작까지
        // 2손가락 탭으로 삼켜버린다 (MultiTouchGestureTracker의 꼬리 보정 전제 조건)
        assertTrue(GestureConfig.MULTI_TOUCH_RELEASE_GRACE_MS > 0L)
        assertTrue(
            GestureConfig.MULTI_TOUCH_RELEASE_GRACE_MS < GestureConfig.TAP_MAX_DURATION_MS / 2
        )
    }
}
