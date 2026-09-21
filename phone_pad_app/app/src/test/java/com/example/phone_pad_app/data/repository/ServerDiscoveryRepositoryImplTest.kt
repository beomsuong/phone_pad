package com.example.phone_pad_app.data.repository

import com.example.phone_pad_app.data.network.ServerDiscoveryClient
import com.example.phone_pad_app.domain.model.DiscoveredServer
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 저장소는 탐색 클라이언트에 그대로 위임한다 — 목록을 다시 정렬하거나 걸러내지 않는다
 * (중복 제거·상한·순서는 클라이언트의 계약이므로 두 곳에서 하면 규칙이 갈라진다).
 */
class ServerDiscoveryRepositoryImplTest {

    private val client: ServerDiscoveryClient = mockk()
    private val repository = ServerDiscoveryRepositoryImpl(client)

    @Test
    fun `discover는 클라이언트 결과를 순서까지 그대로 돌려준다`() = runBlocking {
        val servers = listOf(
            DiscoveredServer("B-PC", "192.168.0.12", 9000),
            DiscoveredServer("A-PC", "192.168.0.11", 9100),
        )
        coEvery { client.discover() } returns servers

        assertEquals(servers, repository.discover())
        coVerify(exactly = 1) { client.discover() }
    }

    @Test
    fun `아무도 찾지 못하면 빈 목록이다`() = runBlocking {
        coEvery { client.discover() } returns emptyList()

        assertTrue(repository.discover().isEmpty())
    }
}
