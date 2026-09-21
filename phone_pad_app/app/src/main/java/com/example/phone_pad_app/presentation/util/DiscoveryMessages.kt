package com.example.phone_pad_app.presentation.util

import com.example.phone_pad_app.domain.model.DiscoveredServer
import com.example.phone_pad_app.domain.model.DiscoveryState

/**
 * "서버 찾기" 화면에 그릴 **한국어 문구**를 고르는 표시 계층 순수 함수 모음.
 *
 * [ConnectionErrorMessages]와 같은 규약이다: 내부 상태([DiscoveryState])는 로직의 계약이고,
 * 사용자 문구는 화면에 그릴 때만 만든다(AGENTS.md 섹션 9). Compose에 의존하지 않아 전 분기를
 * JUnit으로 고정할 수 있고, 다국어가 필요해지면 이 파일 하나만 리소스로 옮기면 된다.
 *
 * [status]의 `when`은 **모든 상태를 나열**한다 — 새 상태를 추가하면 문구를 빠뜨린 채로는
 * 컴파일되지 않는다.
 */
object DiscoveryMessages {

    /** 탐색을 시작하는 버튼의 문구. 찾고 있는 동안에는 비활성화되므로 그대로 둔다. */
    const val SEARCH_BUTTON = "서버 찾기"

    /** 결과가 있을 때 목록 위에 붙이는 안내. */
    const val PICK_HINT = "연결할 서버를 선택하세요"

    /**
     * 아무도 응답하지 않았을 때의 안내.
     *
     * 사용자가 실제로 취할 수 있는 조치만 적는다 — 탐색 실패의 원인은 앱이 구분할 수 없고
     * (브로드캐스트는 응답이 없으면 그냥 조용하다), 수동 IP 입력이라는 확실한 fallback이 있다.
     */
    val NOT_FOUND: String = "서버를 찾지 못했습니다.\n" +
        "- 폰과 PC가 같은 Wi-Fi에 있는지 확인하세요.\n" +
        "- PC에서 Phone Pad 서버가 실행 중인지 확인하세요.\n" +
        "- PC 방화벽이 UDP ${GestureConfig.DISCOVERY_PORT} 포트를 막고 있을 수 있습니다.\n" +
        "- 위 입력란에 IP 주소를 직접 입력해 연결할 수도 있습니다."

    /** 탐색 중 표시. */
    const val SEARCHING = "서버를 찾는 중..."

    /** 버튼만 보여주면 되는 시작 전 상태의 안내. */
    const val IDLE = "같은 Wi-Fi의 PC 서버를 자동으로 찾을 수 있습니다"

    /**
     * 현재 상태에 대응하는 안내 문구. **모든 상태가 문구를 가진다**(빈 문자열 없음).
     */
    fun status(state: DiscoveryState): String = when (state) {
        DiscoveryState.Idle -> IDLE
        DiscoveryState.Searching -> SEARCHING
        is DiscoveryState.Found -> PICK_HINT
        DiscoveryState.NotFound -> NOT_FOUND
    }

    /** 목록 한 줄의 보조 문구 — 실제로 연결할 주소를 숨기지 않고 그대로 보여준다. */
    fun addressLabel(server: DiscoveredServer): String = "${server.host}:${server.port}"

    /** 탐색 중에는 버튼을 눌러도 무시되므로(ViewModel 정책) 화면에서도 막는다. */
    fun isSearchEnabled(state: DiscoveryState): Boolean = state !is DiscoveryState.Searching
}
