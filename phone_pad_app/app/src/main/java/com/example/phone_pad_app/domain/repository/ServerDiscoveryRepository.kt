package com.example.phone_pad_app.domain.repository

import com.example.phone_pad_app.domain.model.DiscoveredServer

/**
 * LAN 안의 PC 서버를 찾는 저장소.
 *
 * [TrackpadRepository]와 분리한 이유: 탐색은 연결 상태 기계와 아무 관계가 없고(세션도,
 * 재연결도, heartbeat도 없다) 수명도 호출 1회로 끝난다. 한 인터페이스에 합치면 연결
 * 저장소의 상태 규약에 탐색이 끌려 들어간다.
 */
interface ServerDiscoveryRepository {

    /**
     * 한 번의 탐색을 수행하고 발견한 서버 목록을 돌려준다.
     *
     * - 총 소요 시간은 [GestureConfig.DISCOVERY_TIMEOUT_MS][com.example.phone_pad_app.presentation.util.GestureConfig.DISCOVERY_TIMEOUT_MS]로 제한된다.
     * - 아무도 응답하지 않으면 빈 목록. **예외를 던지지 않는다** — 네트워크 열거/전송 실패는
     *   "못 찾음"과 사용자 입장에서 같은 결과이고, 수동 IP 입력이라는 fallback이 늘 있다.
     * - 코루틴이 취소되면 소켓을 닫고 [kotlinx.coroutines.CancellationException]을 올린다.
     */
    suspend fun discover(): List<DiscoveredServer>
}
