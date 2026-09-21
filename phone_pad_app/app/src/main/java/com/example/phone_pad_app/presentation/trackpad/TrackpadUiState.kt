package com.example.phone_pad_app.presentation.trackpad

import com.example.phone_pad_app.domain.model.ConnectionState
import com.example.phone_pad_app.domain.model.DiscoveryState
import com.example.phone_pad_app.presentation.util.GestureConfig

/**
 * @param port 연결에 사용할 **TCP** 포트. 자동 탐색으로 고른 서버가 비표준 포트를 쓰면 그 값이
 *   들어오고, 사용자가 호스트를 직접 고치면 [GestureConfig.DEFAULT_PORT]로 되돌아간다
 *   (선택한 서버의 포트가 무관한 IP로 새는 것을 막기 위해 — [TrackpadViewModel] 참조).
 * @param discovery "서버 찾기"의 진행 상태. 연결 상태와 독립이다.
 */
data class TrackpadUiState(
    val connectionState: ConnectionState = ConnectionState.Disconnected,
    val hostInput: String = "",
    val port: Int = GestureConfig.DEFAULT_PORT,
    val discovery: DiscoveryState = DiscoveryState.Idle,
)
