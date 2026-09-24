package com.example.phone_pad_app.presentation.trackpad

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.example.phone_pad_app.domain.model.ConnectionState
import com.example.phone_pad_app.domain.model.DiscoveredServer
import com.example.phone_pad_app.domain.model.DiscoveryState
import com.example.phone_pad_app.presentation.settings.SettingsScreen
import com.example.phone_pad_app.presentation.settings.SettingsViewModel
import com.example.phone_pad_app.presentation.util.GestureConfig
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun TrackpadScreen(
    viewModel: TrackpadViewModel = hiltViewModel(),
    settingsViewModel: SettingsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    val settingsState by settingsViewModel.uiState.collectAsState()

    // Navigation 의존성을 새로 들이지 않고 상태 하나로 화면을 가른다 — 화면이 둘뿐이고
    // 딥링크/백스택 요구도 없어서 라이브러리를 추가할 이유가 없다.
    var showSettings by rememberSaveable { mutableStateOf(false) }

    val state = uiState.connectionState
    // 설정은 연결 전 화면에서만 연다. 연결이 (재연결 등으로) 살아나면 설정 화면을 열어 둔 채로
    // 두지 않고 곧바로 트랙패드로 돌아간다 — 제스처 표면이 설정 화면에 가려지면 안 된다.
    val settingsAvailable = state is ConnectionState.Disconnected || state is ConnectionState.Error

    if (showSettings && settingsAvailable) {
        SettingsScreen(
            settings = settingsState.settings,
            isLoaded = settingsState.isLoaded,
            onMoveSensitivityChange = settingsViewModel::setMoveSensitivity,
            onScrollPxPerStepChange = settingsViewModel::setScrollPxPerStep,
            onResetToDefaults = settingsViewModel::resetToDefaults,
            onBack = { showSettings = false },
        )
        return
    }

    when (state) {
        is ConnectionState.Disconnected -> ConnectPanel(
            hostInput = uiState.hostInput,
            pinInput = uiState.pinInput,
            discovery = uiState.discovery,
            onHostChange = viewModel::onHostInputChange,
            onPinChange = viewModel::onPinInputChange,
            onConnect = viewModel::connect,
            onSearchServers = viewModel::startDiscovery,
            onSelectServer = viewModel::selectServer,
            onOpenSettings = { showSettings = true },
            error = null,
        )
        is ConnectionState.Error -> ConnectPanel(
            hostInput = uiState.hostInput,
            pinInput = uiState.pinInput,
            discovery = uiState.discovery,
            onHostChange = viewModel::onHostInputChange,
            onPinChange = viewModel::onPinInputChange,
            onConnect = viewModel::connect,
            onSearchServers = viewModel::startDiscovery,
            onSelectServer = viewModel::selectServer,
            onOpenSettings = { showSettings = true },
            error = state,
        )
        // 첫 연결에도 "취소"를 준다. 연결 타임아웃(5초) + 핸드셰이크(3초)로 최악 8초가 걸리고,
        // 그 사이 사용자가 IP 오타를 알아차려도 빠져나갈 문이 없으면 앱이 멈춘 것처럼 보인다.
        is ConnectionState.Connecting -> ConnectingPanel(
            message = "연결 중...",
            onCancel = viewModel::cancelConnect,
        )
        // 자동 재연결 중에는 IP 입력 화면으로 되돌리지 않는다 — 사용자가 할 일이 없고,
        // 유실 때마다 화면이 뒤집히면 잠깐의 WiFi 끊김에도 세션이 끝난 것처럼 보인다.
        // 대신 기다리기 싫은 사용자를 위해 "취소"(= 수동 연결 해제)만 내어준다.
        is ConnectionState.Reconnecting -> ConnectingPanel(
            message = "재연결 중… (${state.attempt}/${state.maxAttempts})",
            hostLabel = state.host,
            onCancel = viewModel::disconnect,
        )
        is ConnectionState.Connected -> TrackpadSurface(
            host = state.host,
            // 제스처 판정에 쓰이는 값은 이 컴포지션 시점의 설정값이다. 설정은 연결 전에만
            // 바꿀 수 있으므로 제스처 도중 값이 바뀌는 경합은 없지만, 값이 바뀌면
            // pointerInput 블록이 재시작되도록 key로도 넘긴다(아래 참조).
            moveSensitivity = settingsState.settings.moveSensitivity,
            scrollPxPerStep = settingsState.settings.scrollPxPerStep,
            onMove = viewModel::sendMove,
            onScroll = viewModel::sendScroll,
            onClick = viewModel::sendClick,
            onDoubleClick = viewModel::sendDoubleClick,
            onRightClick = viewModel::sendRightClick,
            onDesktopSwitch = viewModel::sendDesktopSwitch,
            onDragStart = viewModel::sendDragStart,
            onDragEnd = viewModel::sendDragEnd,
            onDisconnect = viewModel::disconnect,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConnectPanel(
    hostInput: String,
    pinInput: String,
    discovery: DiscoveryState,
    onHostChange: (String) -> Unit,
    onPinChange: (String) -> Unit,
    onConnect: () -> Unit,
    onSearchServers: () -> Unit,
    onSelectServer: (DiscoveredServer) -> Unit,
    onOpenSettings: () -> Unit,
    error: ConnectionState.Error?,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        contentAlignment = Alignment.Center,
    ) {
        // 탐색 결과(최대 8줄)가 붙으면 소형 화면·키보드 노출 시 아래 버튼이 잘릴 수 있어
        // 세로 스크롤을 허용한다. 목록 쪽은 같은 방향 스크롤을 겹치지 않도록 일반 Column이다.
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.verticalScroll(rememberScrollState()),
        ) {
            Text(text = "Phone Pad", style = MaterialTheme.typography.headlineMedium)
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "PC 서버의 IP 주소와 PC 화면에 표시된 PIN을 입력하세요",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(24.dp))
            OutlinedTextField(
                value = hostInput,
                onValueChange = onHostChange,
                label = { Text("IP 주소 (예: 192.168.1.10)") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Uri,
                    // IP 다음에는 PIN을 채워야 하므로 여기서 연결이 시작되면 안 된다.
                    imeAction = ImeAction.Next,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(modifier = Modifier.height(12.dp))
            // PIN은 서버가 실행마다 새로 만들어 콘솔/트레이에 띄운다. 자동 탐색으로 서버를
            // 골라도 채워지지 않으므로(탐색 응답에 PIN이 없다) 사용자가 직접 입력한다.
            OutlinedTextField(
                value = pinInput,
                onValueChange = onPinChange,
                label = { Text("PIN 번호 (PC 화면의 6자리)") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    // 힌트일 뿐이다 — 값 검증은 서버가 한다(형식이 바뀌어도 앱이 막지 않는다).
                    keyboardType = KeyboardType.Number,
                    imeAction = ImeAction.Go,
                ),
                keyboardActions = KeyboardActions(onGo = { onConnect() }),
                modifier = Modifier.fillMaxWidth(),
            )
            // 예외 원문 대신 한국어 조치 힌트를 주 메시지로 보여준다 (원문은 보조 줄에 유지).
            ConnectionErrorSection(error)
            Spacer(modifier = Modifier.height(16.dp))
            Button(
                onClick = onConnect,
                // 빈 PIN으로 문을 두드리면 서버의 브루트포스 카운터만 올라간다(5회면 잠김).
                enabled = hostInput.isNotBlank() && pinInput.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("연결")
            }
            // 수동 IP 입력은 그대로 두고, 자동 탐색을 그 아래에 "도우미"로 붙인다
            // (고른 서버는 입력란을 채울 뿐 자동으로 연결하지 않는다).
            ServerDiscoverySection(
                state = discovery,
                onSearch = onSearchServers,
                onSelect = onSelectServer,
            )
            Spacer(modifier = Modifier.height(8.dp))
            // 설정 진입은 여기(연결 전)에만 둔다 — 연결 후 화면은 전체가 제스처 표면이라
            // 버튼을 놓으면 그만큼 트랙패드 면적을 잃고 오터치도 생긴다.
            // material-icons-extended 의존성을 새로 들이지 않으려고 텍스트 버튼으로 둔다.
            TextButton(onClick = onOpenSettings) {
                Text("감도 설정")
            }
        }
    }
}

/**
 * 첫 연결(`Connecting`)과 자동 재연결(`Reconnecting`)이 함께 쓰는 진행 화면.
 *
 * 차이는 두 가지뿐이라 화면을 따로 만들지 않았다:
 * - [hostLabel]: 재연결은 대상이 확정되어 있으므로 어디에 붙는 중인지 보여준다.
 * - [onCancel]이 하는 일: 재연결은 수동 연결 해제, 첫 연결은 시도 취소(오류 없이 IP 입력
 *   화면으로 복귀)다. 둘 다 "빠져나갈 문"이 필요하다 — 재연결은 최대 55초까지 이어지고,
 *   첫 연결도 연결 타임아웃 + 핸드셰이크로 최악 8초가 걸린다.
 */
@Composable
private fun ConnectingPanel(
    message: String,
    hostLabel: String? = null,
    onCancel: (() -> Unit)? = null,
) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Spacer(modifier = Modifier.height(16.dp))
            Text(message)
            if (hostLabel != null) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = hostLabel,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (onCancel != null) {
                Spacer(modifier = Modifier.height(8.dp))
                TextButton(onClick = onCancel) {
                    Text("취소")
                }
            }
        }
    }
}

@Composable
private fun TrackpadSurface(
    host: String,
    moveSensitivity: Float,
    scrollPxPerStep: Float,
    onMove: (Float, Float) -> Unit,
    onScroll: (Int, Int) -> Unit,
    onClick: () -> Unit,
    onDoubleClick: () -> Unit,
    onRightClick: () -> Unit,
    onDesktopSwitch: (String) -> Unit,
    onDragStart: () -> Unit,
    onDragEnd: () -> Unit,
    onDisconnect: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF1A1A2E))
            // key에 감도 값을 넣는 이유: pointerInput 블록은 key가 같으면 재시작되지 않으므로,
            // 값이 바뀌어도 블록 안에서 만들어진 트래커가 **처음 캡처한 낡은 값**을 계속 쓴다.
            // 설정은 연결 전에만 바꿀 수 있어 실제로 이 경로를 타는 일은 드물지만, 그 전제가
            // 깨지는 순간(예: 나중에 연결 중 설정 진입을 허용) 조용히 틀린 감도로 동작하게 된다.
            .pointerInput(moveSensitivity, scrollPxPerStep) {
                // 판정은 전부 순수 Kotlin 트래커가 담당하고, 여기서는
                // "눌린 포인터 개수 + 중심 좌표 + 타임스탬프"만 넘기고 결정대로 콜백을 호출한다.
                //
                // coroutineScope로 한 겹 감싸는 이유: 더블탭 병합을 위해 단일 클릭을
                // DOUBLE_TAP_INTERVAL_MS만큼 미뤄야 하는데, 그 대기 job은 자기를 만든 제스처가
                // 끝난 뒤에도 살아 있어야 한다(대기 중에 들어오는 "두 번째 탭"은 다음 제스처다).
                // awaitEachGesture 블록 안에서 launch하면 제스처마다 스코프가 달라 job을 가로질러
                // 취소할 수 없으므로, 제스처 루프보다 오래 사는 이 스코프에서 관리한다.
                coroutineScope {
                    val doubleTapDetector = DoubleTapDetector()

                    /** 아직 전송되지 않은 "지연된 단일 클릭". 두 번째 탭이 오면 취소된다. */
                    var pendingClickJob: Job? = null

                    /**
                     * 대기 중인 지연 클릭이 있으면 지금 즉시 내보낸다 (취소가 아니라 발사).
                     *
                     * 사용자가 실제로 한 클릭을 잃어버리면 안 되므로, 뒤이어 다른 종류의
                     * 제스처(드래그/스크롤/우클릭)가 시작되는 순간 "더 이상 두 번째 탭을
                     * 기다릴 이유가 없다"고 보고 바로 전송한다. 그대로 뒀다면:
                     * - 드래그: 커서가 이미 옮겨간 뒤 엉뚱한 위치에서 클릭이 나감 (F-1)
                     * - 우클릭: 컨텍스트 메뉴가 뜬 직후 클릭이 도착해 메뉴 항목을 눌러버릴 수 있음 (F-3)
                     */
                    fun flushPendingClick() {
                        if (pendingClickJob != null) {
                            pendingClickJob?.cancel()
                            pendingClickJob = null
                            onClick()
                        }
                    }

                    /**
                     * [DragHoldDetector]의 판정을 그대로 전송으로 옮긴다.
                     *
                     * 승격(Start) 시에는 우클릭과 같은 처리를 함께 한다:
                     * - 대기 중인 지연 클릭을 즉시 발사한다 — 버튼이 눌린 채 커서가 끌려간 뒤에
                     *   도착하면 엉뚱한 위치가 클릭된다 (F-1과 같은 이유).
                     * - 더블탭 감지기를 리셋한다 — 드래그를 사이에 둔 무관한 두 탭이 우연히
                     *   더블탭으로 묶이지 않게 한다 (F-4와 같은 이유).
                     */
                    fun handleDragHold(signal: DragHoldSignal) {
                        when (signal) {
                            DragHoldSignal.Start -> {
                                flushPendingClick()
                                doubleTapDetector.reset()
                                onDragStart()
                            }
                            DragHoldSignal.End -> onDragEnd()
                            DragHoldSignal.None -> Unit
                        }
                    }

                    awaitEachGesture {
                        // 감도는 트래커 생성 시점에 못 박는다 — 제스처 하나가 진행되는 도중에
                        // 배율이 바뀌면 같은 스와이프 안에서 커서 속도가 달라진다.
                        val tracker = MultiTouchGestureTracker(
                            moveSensitivity = moveSensitivity,
                            scrollPxPerStep = scrollPxPerStep,
                        )
                        val dragHold = DragHoldDetector()
                        try {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            var lastTimestamp = System.currentTimeMillis()
                            tracker.onPointerEvent(
                                pointerCount = GestureConfig.SINGLE_POINTER_COUNT,
                                x = down.position.x,
                                y = down.position.y,
                                timestampMs = lastTimestamp,
                            )
                            dragHold.onGestureStart(
                                x = down.position.x,
                                y = down.position.y,
                                timestampMs = lastTimestamp,
                            )

                            while (true) {
                                // 손가락이 완전히 정지해 있으면 새 포인터 이벤트가 아예 오지 않는다
                                // (Android는 움직일 때만 MotionEvent를 준다). 그래서 "제자리 유지
                                // 시간"은 이벤트만 기다려서는 잴 수 없고, 남은 홀드 시간과 경합시켜야
                                // 한다 — 타임아웃이 이기면 그게 곧 "제자리로 버텼다"는 증거다.
                                // 승격 후보가 아니면(이미 승격했거나 탈락) null이 와서 기존처럼
                                // 타임아웃 없이 기다린다.
                                //
                                // 여기서 쓰는 withTimeoutOrNull은 kotlinx의 것이 아니라
                                // AwaitPointerEventScope의 멤버다(import 없이 해석된다) —
                                // 포인터 입력 스코프 전용 구현이라 타임아웃이 제스처 루프 자체를
                                // 취소하지 않고 null만 돌려준다.
                                val remainingHold = dragHold.remainingHoldMs(System.currentTimeMillis())
                                val event = if (remainingHold == null) {
                                    awaitPointerEvent()
                                } else {
                                    withTimeoutOrNull(remainingHold) { awaitPointerEvent() }
                                }

                                if (event == null) {
                                    handleDragHold(dragHold.onHoldTimeout(System.currentTimeMillis()))
                                    continue
                                }

                                lastTimestamp = System.currentTimeMillis()

                                val pressed = event.changes.filter { it.pressed }
                                if (pressed.isEmpty()) break

                                var sumX = 0f
                                var sumY = 0f
                                pressed.forEach {
                                    sumX += it.position.x
                                    sumY += it.position.y
                                }
                                val centroidX = sumX / pressed.size
                                val centroidY = sumY / pressed.size

                                // 손가락 개수 변화 → 드래그 홀드 즉시 해제(확정 스펙 5번).
                                // 승격 판정도 여기서 한 번 더 본다(떨림 이벤트로 타이머가 계속
                                // 갱신되는 동안에도 시간이 차면 승격되도록).
                                handleDragHold(
                                    dragHold.onPointerEvent(
                                        pointerCount = pressed.size,
                                        x = centroidX,
                                        y = centroidY,
                                        timestampMs = lastTimestamp,
                                    )
                                )

                                val decision = tracker.onPointerEvent(
                                    pointerCount = pressed.size,
                                    x = centroidX,
                                    y = centroidY,
                                    timestampMs = lastTimestamp,
                                )

                                // 3번째 손가락이 닿는 순간 = 이 제스처는 더 이상 탭 계열이 아니다.
                                // 대기 중인 지연 클릭을 취소가 아니라 지금 발사하고(F-1/F-3과 같은
                                // 이유), 데스크톱 전환을 사이에 둔 무관한 두 탭이 더블탭으로 묶이지
                                // 않도록 감지기도 리셋한다(F-4와 같은 이유).
                                // 진행 중이던 드래그 홀드는 위 handleDragHold가 손가락 개수 변화로
                                // 이미 DRAG_END를 냈고, DragHoldDetector는 같은 제스처 안에서
                                // 재무장하지 않는다(기존 규칙).
                                if (decision.pointerCount >= GestureConfig.THREE_POINTER_COUNT) {
                                    flushPendingClick()
                                    doubleTapDetector.reset()
                                }

                                val desktopSwitch = decision.desktopSwitch
                                if (desktopSwitch != null) {
                                    // 방향은 트래커가 이미 "전환 결과의 방향"으로 뒤집어 놓았다 —
                                    // 여기서 다시 손대면 매핑이 두 곳으로 갈라진다.
                                    onDesktopSwitch(desktopSwitch)
                                    pressed.forEach { it.consume() }
                                }

                                val move = decision.move
                                val scroll = decision.scroll
                                if (move != null || scroll != null) {
                                    // 이 제스처가 드래그/스크롤로 확정됐다 — 대기 중이던 이전 탭의
                                    // 클릭이 있다면 지금 내보낸다 (F-1, 커서가 옮겨가기 전에).
                                    flushPendingClick()
                                }
                                if (move != null) {
                                    // 드래그 홀드 중이든 아니든 이동 경로는 완전히 동일하다 —
                                    // 서버가 버튼을 누르고 있을 뿐이다 (확정 스펙 4번).
                                    onMove(move.dx, move.dy)
                                }
                                if (scroll != null) {
                                    onScroll(scroll.dx, scroll.dy)
                                }
                                if (move != null || scroll != null) {
                                    pressed.forEach { it.consume() }
                                }
                            }

                            val end = tracker.onGestureEnd(lastTimestamp)
                            // 손가락을 뗀 정상 종료 — 활성 드래그였다면 여기서 버튼을 놓는다.
                            handleDragHold(dragHold.onGestureEnd())

                            // 확정 스펙 7번: 드래그 홀드로 끝난 제스처는 탭/더블탭/클릭 판정을
                            // 아예 하지 않는다. 승격 조건(경과 >= DRAG_HOLD_THRESHOLD_MS)과 탭 조건
                            // (경과 < TAP_MAX_DURATION_MS)이 같은 값을 기준으로 배타적이라 실제로는
                            // 겹치지 않지만, 꼬리 구간 보정 등으로 다른 구간이 탭으로 판정될 여지를
                            // 남기지 않도록 제스처 단위로 명시적으로 막는다.
                            if (!dragHold.hasPromoted) {
                                when (end.clickButton) {
                                    MultiTouchGestureTracker.BUTTON_LEFT -> {
                                        // 대기 중인 지연 클릭은 어느 쪽으로 판정되든 일단 취소한다:
                                        // - 더블탭 확정이면 그 클릭은 DOUBLE_CLICK에 흡수되어 사라져야 하고,
                                        // - 아니면 이 탭이 새 기준이 되므로 낡은 타이머를 남겨둘 이유가 없다.
                                        pendingClickJob?.cancel()
                                        pendingClickJob = null

                                        if (doubleTapDetector.onTap(end.x, end.y, lastTimestamp)) {
                                            // 개별 CLICK 두 개가 아니라 DOUBLE_CLICK 하나만 나간다.
                                            onDoubleClick()
                                        } else {
                                            // 첫 탭 — 두 번째 탭이 올 시간을 준 뒤에야 CLICK을 보낸다.
                                            // (모든 좌클릭이 이만큼 늦어지는 것은 의도된 트레이드오프다)
                                            pendingClickJob = launch {
                                                delay(GestureConfig.DOUBLE_TAP_INTERVAL_MS)
                                                onClick()
                                            }
                                        }
                                    }
                                    MultiTouchGestureTracker.BUTTON_RIGHT -> {
                                        // 우클릭 자체는 지연/병합 로직과 무관하지만, 대기 중인 좌클릭이
                                        // 있으면 컨텍스트 메뉴가 뜨기 전에 먼저 내보내고(F-3), 이 우클릭이
                                        // 이후의 무관한 좌탭과 잘못 묶이지 않도록 더블탭 감지기도 리셋한다(F-4).
                                        flushPendingClick()
                                        doubleTapDetector.reset()
                                        onRightClick()
                                    }
                                    else -> Unit
                                }
                            }
                        } finally {
                            // 제스처가 취소되거나(화면 이탈, 컴포저블 파기 등) 예외로 빠져나가도
                            // 버튼이 눌린 채 남으면 안 된다 — PC 마우스가 영원히 눌린 상태가 된다.
                            // 정상 경로에서는 위에서 이미 해제했으므로 여기서는 None이 돌아온다
                            // (DragHoldDetector.onGestureEnd는 멱등).
                            handleDragHold(dragHold.onGestureEnd())
                        }
                    }
                }
            },
    ) {
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = host,
                color = Color.White.copy(alpha = 0.4f),
                fontSize = 12.sp,
            )
            TextButton(onClick = onDisconnect) {
                Text("연결 해제", color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp)
            }
        }

        Text(
            text = "터치하여 커서 이동\n탭으로 클릭\n더블탭으로 더블클릭\n" +
                "길게 눌렀다 움직여 드래그\n두 손가락 탭으로 우클릭\n두 손가락 드래그로 스크롤\n" +
                "세 손가락으로 좌우로 쓸어 데스크톱 전환",
            color = Color.White.copy(alpha = 0.15f),
            modifier = Modifier.align(Alignment.Center),
            fontSize = 16.sp,
            lineHeight = 24.sp,
        )
    }
}
