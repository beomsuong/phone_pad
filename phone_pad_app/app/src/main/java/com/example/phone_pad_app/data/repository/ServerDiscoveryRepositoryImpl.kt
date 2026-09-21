package com.example.phone_pad_app.data.repository

import com.example.phone_pad_app.data.network.ServerDiscoveryClient
import com.example.phone_pad_app.domain.model.DiscoveredServer
import com.example.phone_pad_app.domain.repository.ServerDiscoveryRepository
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [ServerDiscoveryRepository]의 구현 — UDP 브로드캐스트 탐색을
 * [ServerDiscoveryClient]에 그대로 위임한다.
 *
 * 얇은 이유: 탐색에는 변환할 상태도, 합칠 데이터 소스도 없다. 이 계층이 존재하는 목적은
 * 도메인(UseCase)이 `data/network`를 직접 보지 않게 하는 경계 그 자체다
 * ([TrackpadRepositoryImpl]처럼 채널을 고르거나 세션을 관리할 일이 없다).
 */
@Singleton
class ServerDiscoveryRepositoryImpl @Inject constructor(
    private val client: ServerDiscoveryClient,
) : ServerDiscoveryRepository {

    override suspend fun discover(): List<DiscoveredServer> = client.discover()
}
