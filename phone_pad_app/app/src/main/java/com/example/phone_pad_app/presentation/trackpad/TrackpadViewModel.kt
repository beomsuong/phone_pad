package com.example.phone_pad_app.presentation.trackpad

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.phone_pad_app.domain.model.DiscoveredServer
import com.example.phone_pad_app.domain.model.DiscoveryState
import com.example.phone_pad_app.domain.model.TrackpadEvent
import com.example.phone_pad_app.domain.repository.TrackpadRepository
import com.example.phone_pad_app.domain.usecase.DiscoverServersUseCase
import com.example.phone_pad_app.domain.usecase.SendEventUseCase
import com.example.phone_pad_app.presentation.util.GestureConfig
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
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
    private val discoverServersUseCase: DiscoverServersUseCase,
) : ViewModel() {

    private val _hostInput = MutableStateFlow("")

    /**
     * 연결에 쓸 TCP 포트. 탐색으로 서버를 고르면 그 서버의 포트로 바뀌고,
     * 사용자가 호스트를 직접 고치면 기본값으로 돌아간다([onHostInputChange]).
     */
    private val _port = MutableStateFlow(GestureConfig.DEFAULT_PORT)

    private val _discovery = MutableStateFlow<DiscoveryState>(DiscoveryState.Idle)

    /**
     * 진행 중인 탐색. [viewModelScope]에서 돌기 때문에 화면/ViewModel이 사라지면 자동으로
     * 취소되고([ServerDiscoveryClient][com.example.phone_pad_app.data.network.ServerDiscoveryClient]가
     * 소켓을 닫는다), 연결을 시작하거나 취소할 때는 아래에서 명시적으로 끊는다.
     */
    private var discoveryJob: Job? = null

    val uiState = combine(
        repository.connectionState,
        _hostInput,
        _port,
        _discovery,
    ) { connectionState, host, port, discovery ->
        TrackpadUiState(
            connectionState = connectionState,
            hostInput = host,
            port = port,
            discovery = discovery,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = TrackpadUiState(),
    )

    /**
     * 사용자가 IP를 직접 고쳤다 → 포트를 기본값으로 되돌린다.
     *
     * 탐색으로 고른 서버가 비표준 포트(예: 9100)를 쓰고 있었다면, 그 값을 그대로 둔 채
     * 사용자가 주소만 다른 PC로 바꾸면 **엉뚱한 IP에 엉뚱한 포트로** 붙으러 간다.
     * 포트는 "선택한 그 서버"에만 딸린 값이므로 주소가 바뀌는 순간 무효로 본다.
     */
    fun onHostInputChange(value: String) {
        _hostInput.value = value
        _port.value = GestureConfig.DEFAULT_PORT
    }

    /**
     * LAN에서 서버를 찾는다 (UDP 9002 브로드캐스트).
     *
     * **이미 탐색 중이면 아무것도 하지 않는다** — 재시작으로 고르면 버튼 연타가 매번 소켓을
     * 새로 열고 1.5초 창을 리셋해 사용자에게는 "영원히 찾는 중"으로 보인다. 둘 중 하나를
     * 고르라는 스펙이라 "무시"로 고정한다.
     */
    fun startDiscovery() {
        if (_discovery.value is DiscoveryState.Searching) return
        _discovery.value = DiscoveryState.Searching
        discoveryJob = viewModelScope.launch {
            // runCatching을 쓰면 안 된다 — CancellationException까지 삼켜서, 연결을 시작하며
            // 끊은 탐색이 "서버를 찾지 못했습니다"로 표시된다(실제로 테스트가 잡아낸 버그).
            val servers = try {
                discoverServersUseCase()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 탐색 실패는 사용자 입장에서 "못 찾음"과 같다. 수동 IP 입력이 그대로 fallback이다.
                emptyList()
            }
            _discovery.value = if (servers.isEmpty()) {
                DiscoveryState.NotFound
            } else {
                DiscoveryState.Found(servers)
            }
        }
    }

    /**
     * 목록에서 서버를 골랐다 → 입력란만 채운다. **자동으로 연결하지 않는다.**
     *
     * 탐색 응답은 같은 LAN의 누구나 위조할 수 있으므로, 고른 결과로 곧바로 접속해 버리면
     * 사용자가 "어디에 붙는지" 확인할 기회가 사라진다. 연결은 늘 사용자가 버튼으로 시작한다.
     */
    fun selectServer(server: DiscoveredServer) {
        _hostInput.value = server.host
        _port.value = server.port
    }

    /** 진행 중인 탐색을 끊고 상태를 되돌린다. 연결을 시작하거나 취소할 때 호출한다. */
    private fun stopDiscovery() {
        discoveryJob?.cancel()
        discoveryJob = null
        if (_discovery.value is DiscoveryState.Searching) {
            _discovery.value = DiscoveryState.Idle
        }
    }

    fun connect() {
        val host = _hostInput.value.trim()
        if (host.isBlank()) return
        // 연결 화면을 떠나는 순간 탐색 결과는 쓸모가 없다 — 소켓과 1.5초 창을 여기서 끊는다.
        stopDiscovery()
        // uiState가 아니라 _port를 직접 읽는다 — uiState는 WhileSubscribed(5초) 공유 플로우라
        // 구독자가 없는 순간에는 초기값을 들고 있어, 방금 고른 서버의 포트를 놓칠 수 있다.
        viewModelScope.launch {
            repository.connect(host, _port.value)
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
        // 취소로 연결 화면에 돌아오더라도, 연결을 시작하며 끊었던 탐색을 되살리지는 않는다.
        // (이미 끊겼으면 아무 일도 하지 않는 멱등 호출이다)
        stopDiscovery()
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
