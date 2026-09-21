package com.example.phone_pad_app.domain.usecase

import com.example.phone_pad_app.domain.model.DiscoveredServer
import com.example.phone_pad_app.domain.repository.ServerDiscoveryRepository
import javax.inject.Inject

/**
 * "서버 찾기" 유스케이스.
 *
 * ViewModel이 네트워크 계층([com.example.phone_pad_app.data.network.ServerDiscoveryClient])을
 * 직접 부르지 않도록 하는 경유점이다 (AGENTS.md 섹션 9).
 */
class DiscoverServersUseCase @Inject constructor(
    private val repository: ServerDiscoveryRepository,
) {
    suspend operator fun invoke(): List<DiscoveredServer> = repository.discover()
}
