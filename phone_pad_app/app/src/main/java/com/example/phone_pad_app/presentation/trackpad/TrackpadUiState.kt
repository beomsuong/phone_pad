package com.example.phone_pad_app.presentation.trackpad

import com.example.phone_pad_app.domain.model.ConnectionState
import com.example.phone_pad_app.domain.model.DiscoveryState
import com.example.phone_pad_app.presentation.util.GestureConfig

/**
 * @param port 연결에 사용할 **TCP** 포트. 자동 탐색으로 고른 서버가 비표준 포트를 쓰면 그 값이
 *   들어오고, 사용자가 호스트를 직접 고치면 [GestureConfig.DEFAULT_PORT]로 되돌아간다
 *   (선택한 서버의 포트가 무관한 IP로 새는 것을 막기 위해 — [TrackpadViewModel] 참조).
 * @param pinInput PC 화면에 표시된 PIN. [hostInput]과 완전히 대칭이며 **영속화하지 않는다** —
 *   서버가 실행마다 새 PIN을 생성하므로 저장된 값은 다음 연결 때 거의 항상 틀린 값이다.
 *   자동 탐색으로 서버를 골라도 채워지지 않는다(탐색 응답에 PIN이 없다 — AGENTS.md 섹션 4).
 * @param discovery "서버 찾기"의 진행 상태. 연결 상태와 독립이다.
 */
data class TrackpadUiState(
    val connectionState: ConnectionState = ConnectionState.Disconnected,
    val hostInput: String = "",
    val pinInput: String = "",
    val port: Int = GestureConfig.DEFAULT_PORT,
    val discovery: DiscoveryState = DiscoveryState.Idle,
)
