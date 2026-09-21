package com.example.phone_pad_app.domain.usecase

import com.example.phone_pad_app.domain.model.DiscoveredServer
import com.example.phone_pad_app.domain.repository.ServerDiscoveryRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * ViewModel이 네트워크 계층을 직접 부르지 않게 하는 경유점이 실제로 저장소를 지나는지 고정한다
 * (AGENTS.md 섹션 9 — "ViewModel에서 직접 네트워크 호출 금지").
 */
class DiscoverServersUseCaseTest {

    private val repository: ServerDiscoveryRepository = mockk()
    private val useCase = DiscoverServersUseCase(repository)

    @Test
    fun `invoke는 저장소의 discover 결과를 그대로 돌려준다`() = runBlocking {
        val servers = listOf(DiscoveredServer("PC", "192.168.0.11", 9000))
        coEvery { repository.discover() } returns servers

        assertEquals(servers, useCase())
        coVerify(exactly = 1) { repository.discover() }
    }
}
