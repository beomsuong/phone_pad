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
            onClick = viewModel::sendClick,
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
    onClick: () -> Unit,
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
                        if (move != null) {
                            onMove(move.dx, move.dy)
                            pressed.forEach { it.consume() }
                        }
                    }

                    when (tracker.onGestureEnd(lastTimestamp).clickButton) {
                        MultiTouchGestureTracker.BUTTON_LEFT -> onClick()
                        MultiTouchGestureTracker.BUTTON_RIGHT -> onRightClick()
                        else -> Unit
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
            text = "터치하여 커서 이동\n탭으로 클릭\n두 손가락 탭으로 우클릭",
            color = Color.White.copy(alpha = 0.15f),
            modifier = Modifier.align(Alignment.Center),
            fontSize = 16.sp,
            lineHeight = 24.sp,
        )
    }
}
