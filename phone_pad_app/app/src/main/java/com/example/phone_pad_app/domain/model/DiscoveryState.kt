package com.example.phone_pad_app.domain.model

/**
 * "서버 찾기"의 진행 상태.
 *
 * [ConnectionState]와 **섞지 않는다**: 탐색은 연결이 아니고, 탐색에 실패해도 수동 IP 입력이라는
 * fallback이 그대로 살아 있다. 연결 상태를 오염시키면 "서버를 못 찾음"이 "연결 실패"처럼 보인다.
 *
 * 결과가 빈 목록이면 [Found]가 아니라 [NotFound]다 — 화면이 "빈 목록"과 "못 찾음"을 따로
 * 판단하지 않도록 상태 쪽에서 한 번만 정한다.
 */
sealed interface DiscoveryState {

    /** 아직 찾아본 적이 없거나, 직전 결과를 더 이상 보여주지 않는 상태. */
    object Idle : DiscoveryState

    /** 브로드캐스트 후 응답을 기다리는 중. */
    object Searching : DiscoveryState

    /**
     * 한 대 이상 찾은 상태.
     *
     * @param servers 발견 순서를 유지한 목록(중복 제거·상한 적용 완료). 비어 있을 수 없다.
     */
    data class Found(val servers: List<DiscoveredServer>) : DiscoveryState {
        init {
            require(servers.isNotEmpty()) { "Found는 빈 목록을 가질 수 없다 - NotFound를 쓸 것" }
        }
    }

    /** 창이 끝날 때까지 아무 응답도 오지 않았다. */
    object NotFound : DiscoveryState
}
