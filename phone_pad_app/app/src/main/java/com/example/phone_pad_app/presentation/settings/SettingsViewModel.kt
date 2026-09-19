package com.example.phone_pad_app.presentation.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.phone_pad_app.domain.model.GestureSettings
import com.example.phone_pad_app.domain.repository.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 감도 설정 화면의 ViewModel.
 *
 * 저장은 **슬라이더를 놓을 때만** 일어난다(`onValueChangeFinished`) — 드래그 중에는 화면이
 * 로컬 상태로만 움직인다. 매 프레임 `DataStore.edit`를 호출하면 프레임마다 디스크 쓰기가 생긴다.
 *
 * 변경 시 현재 값을 [SettingsRepository.settings]에서 다시 읽는 이유: [uiState]는
 * `WhileSubscribed`라 구독자가 없는 동안 값이 갱신되지 않을 수 있는데, 저장은 그 상태와
 * 무관하게 항상 "디스크에 있는 최신 값"을 기준으로 한 필드만 바꿔야 하기 때문이다.
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    val uiState = settingsRepository.settings
        .map { SettingsUiState(settings = it, isLoaded = true) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = SettingsUiState(),
        )

    /** 포인터 속도 슬라이더를 놓았을 때. 범위 밖 값은 저장소가 보정한다. */
    fun setMoveSensitivity(value: Float) {
        updateSettings { it.copy(moveSensitivity = value) }
    }

    /**
     * 스크롤 스텝 거리를 저장한다.
     *
     * 화면이 보여주는 "스크롤 속도"와 방향이 반대인 값이므로, 슬라이더 위치 → px/step 변환은
     * 호출부([ScrollSpeedSlider])가 이미 끝낸 상태로 들어온다.
     */
    fun setScrollPxPerStep(value: Float) {
        updateSettings { it.copy(scrollPxPerStep = value) }
    }

    /** "기본값으로 복원" — 저장된 값을 지워 [GestureSettings.DEFAULT] 상태로 되돌린다. */
    fun resetToDefaults() {
        viewModelScope.launch {
            settingsRepository.reset()
        }
    }

    private fun updateSettings(transform: (GestureSettings) -> GestureSettings) {
        viewModelScope.launch {
            val current = settingsRepository.settings.first()
            settingsRepository.update(transform(current))
        }
    }
}
