package com.example.phone_pad_app.presentation.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Divider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.phone_pad_app.domain.model.GestureSettings
import java.util.Locale

/**
 * 감도 설정 화면.
 *
 * 연결 전(Disconnected/Error) 화면에서만 진입할 수 있다 — 연결된 뒤에는 화면 전체가 제스처
 * 표면이라 설정 진입 버튼이 들어갈 자리가 없고, 제스처 도중 감도가 바뀌는 경합도 피할 수 있다.
 *
 * 상태를 직접 들고 있지 않은 stateless 컴포저블이라 호출부가 ViewModel을 소유한다.
 *
 * @param isLoaded 저장소에서 값을 아직 못 읽었으면 false — 슬라이더를 비활성화해서, 디스크에서
 *        늦게 도착할 값을 사용자가 만진 기본값으로 덮어쓰는 사고를 막는다.
 */
@Composable
fun SettingsScreen(
    settings: GestureSettings,
    isLoaded: Boolean,
    onMoveSensitivityChange: (Float) -> Unit,
    onScrollPxPerStepChange: (Float) -> Unit,
    onResetToDefaults: () -> Unit,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("← 뒤로") }
            Text(
                text = "감도 설정",
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(start = 8.dp),
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // 포인터 속도 — 저장값이 곧 슬라이더 값이다(클수록 빠름).
        SettingSlider(
            title = "포인터 속도",
            // 배율은 숫자 자체보다 "기본값 대비 어느 정도인지"가 중요해 배수로 보여준다.
            valueLabel = { String.format(Locale.US, "%.1f×", it) },
            description = "손가락을 움직인 거리 대비 PC 커서가 움직이는 배율입니다.",
            storedValue = settings.moveSensitivity,
            valueRange = GestureSettings.MOVE_SENSITIVITY_RANGE,
            enabled = isLoaded,
            onCommit = onMoveSensitivityChange,
        )

        Spacer(modifier = Modifier.height(24.dp))

        // 스크롤 속도 — 저장값(px/step)은 클수록 "느린" 값이라 슬라이더에서는 좌우를 뒤집는다
        // (오른쪽 = 빠름). 변환은 ScrollSpeedSlider가 양방향 대칭으로 책임진다.
        SettingSlider(
            title = "스크롤 속도",
            valueLabel = { sliderValue ->
                String.format(
                    Locale.US,
                    "%.0f px / 1스텝",
                    ScrollSpeedSlider.toPxPerStep(sliderValue),
                )
            },
            description = "두 손가락으로 얼마나 움직여야 휠 한 칸이 굴러가는지 정합니다. " +
                "오른쪽으로 갈수록 적게 움직여도 많이 스크롤됩니다.",
            storedValue = ScrollSpeedSlider.toSliderValue(settings.scrollPxPerStep),
            valueRange = GestureSettings.SCROLL_PX_PER_STEP_RANGE,
            enabled = isLoaded,
            onCommit = { onScrollPxPerStepChange(ScrollSpeedSlider.toPxPerStep(it)) },
        )

        Spacer(modifier = Modifier.height(32.dp))
        Divider()
        Spacer(modifier = Modifier.height(16.dp))

        OutlinedButton(
            onClick = onResetToDefaults,
            enabled = isLoaded,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("기본값으로 복원")
        }

        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "탭·더블탭·드래그 판정 시간처럼 서로 얽힌 값들은 오동작을 막기 위해 고정되어 있습니다.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * 제목 + 현재 값 + 슬라이더 한 벌.
 *
 * 드래그 중에는 [storedValue]가 아니라 로컬 상태만 움직이고, 손을 뗀 순간([onCommit])에만
 * 저장한다 — 매 프레임 DataStore에 쓰면 프레임마다 디스크 I/O가 발생한다.
 * [storedValue]가 바깥에서 바뀌면("기본값으로 복원") `remember`의 key가 바뀌어 로컬 상태도 따라간다.
 */
@Composable
private fun SettingSlider(
    title: String,
    valueLabel: (Float) -> String,
    description: String,
    storedValue: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    enabled: Boolean,
    onCommit: (Float) -> Unit,
) {
    var sliderValue by remember(storedValue) { mutableStateOf(storedValue) }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(text = title, style = MaterialTheme.typography.titleMedium)
        Text(text = valueLabel(sliderValue), style = MaterialTheme.typography.titleMedium)
    }

    Slider(
        value = sliderValue,
        onValueChange = { sliderValue = it },
        onValueChangeFinished = { onCommit(sliderValue) },
        valueRange = valueRange,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(),
    )

    Text(
        text = description,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
