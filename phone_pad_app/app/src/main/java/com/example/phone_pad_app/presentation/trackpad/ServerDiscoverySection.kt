package com.example.phone_pad_app.presentation.trackpad

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.phone_pad_app.domain.model.DiscoveredServer
import com.example.phone_pad_app.domain.model.DiscoveryState
import com.example.phone_pad_app.presentation.util.DiscoveryMessages

/**
 * 연결 화면(Disconnected/Error)의 "서버 찾기" 영역.
 *
 * [ConnectionErrorSection]과 같은 이유로 별도 파일에 둔다 — `TrackpadScreen`의 변경 면적을
 * 최소화해 제스처 코드와 충돌하지 않게 한다.
 *
 * 서버를 고르면 **입력란만 채우고 연결하지는 않는다**(확정 스펙). 그래서 목록은 "연결 버튼"이
 * 아니라 "IP를 대신 입력해 주는 도우미"로 그린다.
 */
@Composable
fun ServerDiscoverySection(
    state: DiscoveryState,
    onSearch: () -> Unit,
    onSelect: (DiscoveredServer) -> Unit,
) {
    Spacer(modifier = Modifier.height(16.dp))

    OutlinedButton(
        onClick = onSearch,
        enabled = DiscoveryMessages.isSearchEnabled(state),
        modifier = Modifier.fillMaxWidth(),
    ) {
        // 버튼 내부는 RowScope다 — 가로로 놓이므로 size/width를 쓴다.
        if (state is DiscoveryState.Searching) {
            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            Spacer(modifier = Modifier.width(8.dp))
        }
        Text(DiscoveryMessages.SEARCH_BUTTON)
    }

    Spacer(modifier = Modifier.height(8.dp))
    Text(
        text = DiscoveryMessages.status(state),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    if (state is DiscoveryState.Found) {
        Spacer(modifier = Modifier.height(8.dp))
        // 결과는 상한이 8개(GestureConfig.DISCOVERY_MAX_RESULTS)라 LazyColumn을 쓰지 않는다 —
        // 스크롤되는 부모(연결 화면) 안에 같은 방향 스크롤을 겹치지 않기 위한 선택이기도 하다.
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            state.servers.forEach { server ->
                ServerRow(server = server, onSelect = onSelect)
            }
        }
    }
}

@Composable
private fun ServerRow(server: DiscoveredServer, onSelect: (DiscoveredServer) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onSelect(server) }
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.Start,
    ) {
        Text(text = server.name, style = MaterialTheme.typography.bodyMedium)
        Text(
            // 실제로 연결할 주소를 숨기지 않는다 — 이름은 서버가 보낸 표시용 값이라 위조될 수 있다.
            text = DiscoveryMessages.addressLabel(server),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Divider()
    }
}
