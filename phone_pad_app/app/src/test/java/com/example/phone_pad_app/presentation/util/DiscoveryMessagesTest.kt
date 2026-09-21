package com.example.phone_pad_app.presentation.util

import com.example.phone_pad_app.domain.model.DiscoveredServer
import com.example.phone_pad_app.domain.model.DiscoveryState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 문구 **계약**만 고정한다 — 문장 전문을 박아 넣지 않는다(다듬을 수 있어야 한다).
 * [ConnectionErrorMessagesTest]와 같은 규약이다 (AGENTS.md 섹션 9).
 */
class DiscoveryMessagesTest {

    private val allStates = listOf(
        DiscoveryState.Idle,
        DiscoveryState.Searching,
        DiscoveryState.Found(listOf(DiscoveredServer("PC", "192.168.0.11", 9000))),
        DiscoveryState.NotFound,
    )

    @Test
    fun `모든 상태가 문구를 가진다`() {
        allStates.forEach { state ->
            val text = DiscoveryMessages.status(state)
            assertTrue("$state 에 문구가 없다", text.isNotBlank())
        }
    }

    @Test
    fun `상태마다 다른 문구를 쓴다`() {
        val texts = allStates.map { DiscoveryMessages.status(it) }

        assertEquals(allStates.size, texts.toSet().size)
    }

    @Test
    fun `못 찾았을 때는 수동 입력이라는 탈출로를 알려준다`() {
        val text = DiscoveryMessages.status(DiscoveryState.NotFound)

        assertTrue("Wi-Fi 안내가 없다", text.contains("Wi-Fi"))
        assertTrue("수동 입력 안내가 없다", text.contains("직접 입력"))
        // 방화벽 안내는 실제 포트 번호를 담아야 사용자가 규칙을 만들 수 있다.
        assertTrue(text.contains(GestureConfig.DISCOVERY_PORT.toString()))
    }

    @Test
    fun `내부 상태 문자열이 사용자 문구에 새지 않는다`() {
        allStates.forEach { state ->
            val text = DiscoveryMessages.status(state)
            assertFalse("$state 의 클래스명이 문구에 노출됐다", text.contains("DiscoveryState"))
            assertFalse(text.contains("Searching"))
            assertFalse(text.contains("NotFound"))
        }
    }

    @Test
    fun `주소 라벨은 실제 연결할 host와 port를 그대로 보여준다`() {
        // 이름은 서버가 보낸 표시용 값(위조 가능)이라, 어디에 붙는지는 주소로 보여줘야 한다.
        val label = DiscoveryMessages.addressLabel(DiscoveredServer("어떤PC", "192.168.0.11", 9100))

        assertEquals("192.168.0.11:9100", label)
    }

    @Test
    fun `탐색 중에는 찾기 버튼이 비활성화된다`() {
        assertFalse(DiscoveryMessages.isSearchEnabled(DiscoveryState.Searching))
        assertTrue(DiscoveryMessages.isSearchEnabled(DiscoveryState.Idle))
        assertTrue(DiscoveryMessages.isSearchEnabled(DiscoveryState.NotFound))
        assertTrue(
            DiscoveryMessages.isSearchEnabled(
                DiscoveryState.Found(listOf(DiscoveredServer("PC", "192.168.0.11", 9000)))
            )
        )
    }
}
