package com.example.phone_pad_app.presentation.trackpad

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.phone_pad_app.domain.model.TrackpadEvent
import com.example.phone_pad_app.domain.repository.TrackpadRepository
import com.example.phone_pad_app.domain.usecase.SendEventUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class TrackpadViewModel @Inject constructor(
    private val sendEventUseCase: SendEventUseCase,
    private val repository: TrackpadRepository,
) : ViewModel() {

    private val _hostInput = MutableStateFlow("")

    val uiState = combine(
        repository.connectionState,
        _hostInput,
    ) { connectionState, host ->
        TrackpadUiState(connectionState = connectionState, hostInput = host)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = TrackpadUiState(),
    )

    fun onHostInputChange(value: String) {
        _hostInput.value = value
    }

    fun connect() {
        val host = _hostInput.value.trim()
        if (host.isBlank()) return
        viewModelScope.launch {
            repository.connect(host, uiState.value.port)
        }
    }

    /**
     * 진행 중인 첫 연결 시도를 취소하고 IP 입력 화면으로 되돌린다 (`Connecting` 전용).
     *
     * [disconnect]와 나눠 둔 이유는 되돌아가는 상태가 다르기 때문이 아니라(둘 다
     * `Disconnected`다) 의미가 다르기 때문이다 — 취소는 살아있는 연결이나 자동 재연결을
     * 건드리면 안 된다. 그 구분은 repository가 상태를 보고 판단한다.
     */
    fun cancelConnect() {
        viewModelScope.launch {
            repository.cancelConnect()
        }
    }

    fun disconnect() {
        viewModelScope.launch {
            repository.disconnect()
        }
    }

    fun sendMove(dx: Float, dy: Float) {
        viewModelScope.launch {
            runCatching { sendEventUseCase(TrackpadEvent.Move(dx, dy)) }
        }
    }

    /** 1손가락 탭 → 좌클릭. `{"type":"CLICK","button":"left"}` (TCP) */
    fun sendClick() {
        viewModelScope.launch {
            runCatching { sendEventUseCase(TrackpadEvent.Click(MultiTouchGestureTracker.BUTTON_LEFT)) }
        }
    }

    /**
     * 1손가락 더블탭 → 더블클릭. `{"type":"DOUBLE_CLICK","button":"left"}` (TCP)
     *
     * 개별 CLICK 두 개가 아니라 이 이벤트 하나만 보낸다 — 두 탭의 병합 판정은
     * [DoubleTapDetector]가, 지연 전송은 `TrackpadScreen`이 이미 끝낸 상태로 들어온다.
     */
    fun sendDoubleClick() {
        viewModelScope.launch {
            runCatching {
                sendEventUseCase(
                    TrackpadEvent.DoubleClick(MultiTouchGestureTracker.BUTTON_LEFT)
                )
            }
        }
    }

    /**
     * 2손가락 드래그 → 휠 스크롤. `{"type":"SCROLL","dx":0,"dy":-3}` (TCP)
     *
     * [dx]/[dy]는 픽셀이 아니라 정수 스텝(휠 노치)이다 — px→스텝 변환과 잔차 누적은
     * [MultiTouchGestureTracker]가 끝낸 뒤라 여기서는 그대로 전달만 한다.
     */
    fun sendScroll(dx: Int, dy: Int) {
        viewModelScope.launch {
            runCatching { sendEventUseCase(TrackpadEvent.Scroll(dx, dy)) }
        }
    }

    /**
     * 탭홀드 승격 → 드래그 시작. `{"type":"DRAG_START"}` (TCP)
     *
     * 이 시점부터 PC 왼쪽 버튼이 눌린 채로 유지된다. 이후의 커서 이동은 [sendMove]가
     * 그대로 담당한다 — 드래그 전용 이동 이벤트는 없다 (AGENTS.md 섹션 4).
     */
    fun sendDragStart() {
        viewModelScope.launch {
            runCatching { sendEventUseCase(TrackpadEvent.DragStart) }
        }
    }

    /**
     * 드래그 종료. `{"type":"DRAG_END"}` (TCP)
     *
     * 손가락을 뗀 경우뿐 아니라 손가락 개수 변화·제스처 취소로 드래그가 끊기는 경우에도
     * 호출된다 — 이게 나가지 않으면 PC 버튼이 눌린 채로 남는다.
     */
    fun sendDragEnd() {
        viewModelScope.launch {
            runCatching { sendEventUseCase(TrackpadEvent.DragEnd) }
        }
    }

    /**
     * 3손가락 수평 스와이프 → 가상 데스크톱 전환.
     * `{"type":"DESKTOP_SWITCH","direction":"left"}` (TCP)
     *
     * [direction]은 **전환 결과의 방향**([MultiTouchGestureTracker.DIRECTION_LEFT] /
     * [MultiTouchGestureTracker.DIRECTION_RIGHT])이다. 손가락 방향 → 와이어 방향 뒤집기는
     * [MultiTouchGestureTracker]가 이미 끝낸 뒤라 여기서는 그대로 전달만 한다 —
     * 이 계층이 한 번 더 뒤집으면 매핑이 두 곳으로 갈라진다.
     */
    fun sendDesktopSwitch(direction: String) {
        viewModelScope.launch {
            runCatching { sendEventUseCase(TrackpadEvent.DesktopSwitch(direction)) }
        }
    }

    /** 2손가락 탭 → 우클릭. `{"type":"CLICK","button":"right"}` (TCP) */
    fun sendRightClick() {
        viewModelScope.launch {
            runCatching { sendEventUseCase(TrackpadEvent.Click(MultiTouchGestureTracker.BUTTON_RIGHT)) }
        }
    }
}
