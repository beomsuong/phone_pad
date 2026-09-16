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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
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
            onDisconnect = viewModel::disconnect,
        )
    }
}

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
    onDisconnect: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF1A1A2E))
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val startPosition: Offset = down.position
                    val startTime = System.currentTimeMillis()
                    var isDrag = false

                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull() ?: break

                        if (change.positionChange() != Offset.Zero) {
                            val totalMoved = (change.position - startPosition).getDistance()
                            if (totalMoved > GestureConfig.MOVE_MIN_DISTANCE_PX) {
                                val delta = change.positionChange()
                                onMove(
                                    delta.x * GestureConfig.MOVE_SENSITIVITY,
                                    delta.y * GestureConfig.MOVE_SENSITIVITY,
                                )
                                change.consume()
                            }
                            if (totalMoved > GestureConfig.TAP_MAX_DISTANCE_PX) {
                                isDrag = true
                            }
                        }

                        if (!change.pressed) break
                    }

                    val elapsed = System.currentTimeMillis() - startTime
                    if (!isDrag && elapsed < GestureConfig.TAP_MAX_DURATION_MS) {
                        onClick()
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
            text = "터치하여 커서 이동\n탭으로 클릭",
            color = Color.White.copy(alpha = 0.15f),
            modifier = Modifier.align(Alignment.Center),
            fontSize = 16.sp,
            lineHeight = 24.sp,
        )
    }
}
