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
    fun `제스처 임계값은 MOVE 최소 거리가 탭 최대 거리보다 작다`() {
        assertTrue(GestureConfig.MOVE_MIN_DISTANCE_PX < GestureConfig.TAP_MAX_DISTANCE_PX)
        assertTrue(GestureConfig.MOVE_SENSITIVITY > 0f)
        assertTrue(GestureConfig.TAP_MAX_DURATION_MS > 0L)
    }
}
