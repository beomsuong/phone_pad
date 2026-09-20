package com.example.phone_pad_app.presentation.trackpad

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.phone_pad_app.domain.model.ConnectionState
import com.example.phone_pad_app.presentation.util.ConnectionErrorMessages

/**
 * 연결 화면의 오류 표시.
 *
 * 두 줄로 나누는 이유:
 * - **주 메시지**는 예외 원문이 아니라 "무엇을 확인해야 하는지"다. 한국어 사용자에게
 *   `failed to connect to /192.168.0.5 (port 9000) from /:: (port 41822) after 21000ms`는
 *   아무 조치도 알려주지 않는다.
 * - **보조 줄**에는 그 원문을 작게 남긴다. 숨겨 버리면 예상 못 한 실패가 왔을 때 사용자가
 *   화면 하나로 상황을 전달할 수 없다.
 *
 * 원인 종류를 모르는 폴백([ConnectionErrorKind.UNKNOWN][com.example.phone_pad_app.domain.model.ConnectionErrorKind.UNKNOWN])
 * 에서는 주 메시지가 이미 원문을 품고 있어 보조 줄이 생략된다 — 같은 문자열을 두 번 보여주지 않는다.
 *
 * 별도 파일로 둔 것은 `TrackpadScreen`의 변경 면적을 줄이기 위해서다(연결 화면을 손보는
 * 다른 작업과 충돌하지 않도록).
 */
@Composable
fun ConnectionErrorSection(error: ConnectionState.Error?) {
    if (error == null) return
    val detail = ConnectionErrorMessages.detail(error.kind, error.message)

    Spacer(modifier = Modifier.height(8.dp))
    Text(
        text = ConnectionErrorMessages.userMessage(error.kind, error.message),
        color = MaterialTheme.colorScheme.error,
        style = MaterialTheme.typography.bodyMedium,
    )
    if (detail != null) {
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = detail,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}
