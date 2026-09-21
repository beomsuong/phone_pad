package com.example.phone_pad_app.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "빈 목록"과 "못 찾음"을 두 곳에서 판단하지 않도록, 상태 쪽에서 한 번만 정한다.
 */
class DiscoveryStateTest {

    @Test
    fun `Found는 빈 목록을 가질 수 없다`() {
        val thrown = runCatching { DiscoveryState.Found(emptyList()) }.exceptionOrNull()

        assertTrue(thrown is IllegalArgumentException)
    }

    @Test
    fun `Found는 발견 순서를 그대로 들고 있는다`() {
        val servers = listOf(
            DiscoveredServer("B", "192.168.0.12", 9000),
            DiscoveredServer("A", "192.168.0.11", 9000),
        )

        assertEquals(servers, DiscoveryState.Found(servers).servers)
    }

    @Test
    fun `탐색 상태는 연결 상태 계층에 속하지 않는다`() {
        // 탐색 실패는 연결 실패가 아니다 — 수동 IP 입력이라는 fallback이 그대로 살아 있다.
        // 두 상태를 한 계층에 합치면 "못 찾음"이 "연결 실패"처럼 화면에 나간다.
        val discovery: Any = DiscoveryState.NotFound

        assertTrue(discovery !is ConnectionState)
        assertTrue(discovery is DiscoveryState)
    }
}
