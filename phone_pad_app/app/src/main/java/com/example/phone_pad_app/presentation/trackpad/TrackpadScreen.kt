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
import androidx.compose.foundation.text.KeyboardActions
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
import com.example.phone_pad_app.presentation.util.GestureConfig
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun TrackpadScreen(viewModel: TrackpadViewModel = hiltViewModel()) {
    val uiState by viewModel.uiState.collectAsState()

    when (val state = uiState.connectionState) {
        is ConnectionState.Disconnected -> ConnectPanel(
            hostInput = uiState.hostInput,
            onHostChange = viewModel::onHostInputChange,
            onConnect = viewModel::connect,
            errorMessage = null,
        )
        is ConnectionState.Error -> ConnectPanel(
            hostInput = uiState.hostInput,
            onHostChange = viewModel::onHostInputChange,
            onConnect = viewModel::connect,
            errorMessage = state.message,
        )
        is ConnectionState.Connecting -> ConnectingPanel()
        is ConnectionState.Connected -> TrackpadSurface(
            host = state.host,
            onMove = viewModel::sendMove,
            onScroll = viewModel::sendScroll,
            onClick = viewModel::sendClick,
            onDoubleClick = viewModel::sendDoubleClick,
            onRightClick = viewModel::sendRightClick,
            onDisconnect = viewModel::disconnect,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConnectPanel(
    hostInput: String,
    onHostChange: (String) -> Unit,
    onConnect: () -> Unit,
    errorMessage: String?,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(text = "Phone Pad", style = MaterialTheme.typography.headlineMedium)
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "PC 서버의 IP 주소를 입력하세요",
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
                    imeAction = ImeAction.Go,
                ),
                keyboardActions = KeyboardActions(onGo = { onConnect() }),
                modifier = Modifier.fillMaxWidth(),
            )
            if (errorMessage != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = errorMessage,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
            Button(
                onClick = onConnect,
                enabled = hostInput.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("연결")
            }
        }
    }
}

@Composable
private fun ConnectingPanel() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Spacer(modifier = Modifier.height(16.dp))
            Text("연결 중...")
        }
    }
}

@Composable
private fun TrackpadSurface(
    host: String,
    onMove: (Float, Float) -> Unit,
    onScroll: (Int, Int) -> Unit,
    onClick: () -> Unit,
    onDoubleClick: () -> Unit,
    onRightClick: () -> Unit,
    onDisconnect: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF1A1A2E))
            .pointerInput(Unit) {
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

                    awaitEachGesture {
                        val tracker = MultiTouchGestureTracker()
                        val down = awaitFirstDown(requireUnconsumed = false)
                        var lastTimestamp = System.currentTimeMillis()
                        tracker.onPointerEvent(
                            pointerCount = GestureConfig.SINGLE_POINTER_COUNT,
                            x = down.position.x,
                            y = down.position.y,
                            timestampMs = lastTimestamp,
                        )

                        while (true) {
                            val event = awaitPointerEvent()
                            lastTimestamp = System.currentTimeMillis()

                            val pressed = event.changes.filter { it.pressed }
                            if (pressed.isEmpty()) break

                            var sumX = 0f
                            var sumY = 0f
                            pressed.forEach {
                                sumX += it.position.x
                                sumY += it.position.y
                            }

                            val decision = tracker.onPointerEvent(
                                pointerCount = pressed.size,
                                x = sumX / pressed.size,
                                y = sumY / pressed.size,
                                timestampMs = lastTimestamp,
                            )

                            val move = decision.move
                            val scroll = decision.scroll
                            if (move != null || scroll != null) {
                                // 이 제스처가 드래그/스크롤로 확정됐다 — 대기 중이던 이전 탭의
                                // 클릭이 있다면 지금 내보낸다 (F-1, 커서가 옮겨가기 전에).
                                flushPendingClick()
                            }
                            if (move != null) {
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
                "두 손가락 탭으로 우클릭\n두 손가락 드래그로 스크롤",
            color = Color.White.copy(alpha = 0.15f),
            modifier = Modifier.align(Alignment.Center),
            fontSize = 16.sp,
            lineHeight = 24.sp,
        )
    }
}
