package com.example.phone_pad_app.presentation.settings

import com.example.phone_pad_app.domain.model.GestureSettings

/**
 * 설정 화면의 상태.
 *
 * @param settings 현재 저장된 감도 설정. 저장소가 항상 유효한 값을 방출하므로 UI는 별도 검증 없이
 *        슬라이더 값으로 바로 쓸 수 있다.
 * @param isLoaded 저장소에서 최소 한 번 값을 받았는지. 아직이면 [GestureSettings.DEFAULT]가 들어 있어
 *        화면은 정상적으로 그려지지만, "기본값이라서 기본값"인지 "아직 못 읽어서 기본값"인지는
 *        구분되어야 한다(디스크 읽기가 한 프레임 늦게 도착하는 동안 사용자가 슬라이더를 만지면
 *        낡은 값을 저장하게 되므로 그 사이에는 조작을 막는다).
 */
data class SettingsUiState(
    val settings: GestureSettings = GestureSettings.DEFAULT,
    val isLoaded: Boolean = false,
)
