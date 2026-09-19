package com.example.phone_pad_app.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import com.example.phone_pad_app.domain.model.GestureSettings
import com.example.phone_pad_app.domain.repository.SettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [SettingsRepository]의 DataStore(Preferences) 구현.
 *
 * 읽기·쓰기 양쪽에서 [GestureSettings.sanitized]를 통과시키는 것이 핵심이다:
 * - **읽기**: 앱의 이전 버전이 저장한 값, 범위가 좁아진 뒤의 값, 손상된 파일에서 복구된 값이
 *   그대로 제스처 판정기로 흘러들어가지 않게 한다.
 * - **쓰기**: UI가 실수로 범위 밖 값을 넘겨도 디스크에는 유효한 값만 남는다.
 *
 * `IOException`은 흔한 일시적 실패(디스크 가득 참, 파일 권한 등)이므로 스트림을 끊지 않고
 * 빈 Preferences로 대체한다 — 설정을 못 읽는 것 때문에 트랙패드가 멈추면 안 되고, 그 경우
 * 기본 감도로 동작하는 편이 낫다. 다른 예외는 삼키지 않고 그대로 올린다.
 */
@Singleton
class DataStoreSettingsRepository @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) : SettingsRepository {

    override val settings: Flow<GestureSettings> = dataStore.data
        .catch { cause ->
            if (cause is IOException) emit(emptyPreferences()) else throw cause
        }
        .map { preferences ->
            GestureSettings(
                // 키가 없으면(= 한 번도 저장한 적 없으면) 기본값. 두 값은 서로 독립적으로
                // 저장되므로 한쪽만 저장된 중간 상태도 정상 동작해야 한다.
                moveSensitivity = preferences[KEY_MOVE_SENSITIVITY]
                    ?: GestureSettings.DEFAULT.moveSensitivity,
                scrollPxPerStep = preferences[KEY_SCROLL_PX_PER_STEP]
                    ?: GestureSettings.DEFAULT.scrollPxPerStep,
            ).sanitized()
        }

    override suspend fun update(settings: GestureSettings) {
        val safe = settings.sanitized()
        dataStore.edit { preferences ->
            preferences[KEY_MOVE_SENSITIVITY] = safe.moveSensitivity
            preferences[KEY_SCROLL_PX_PER_STEP] = safe.scrollPxPerStep
        }
    }

    override suspend fun reset() {
        // 기본값을 "써넣는" 대신 키를 지운다 — 나중에 기본값 상수가 바뀌면 복원한 사용자는
        // 새 기본값을 따라가는 것이 맞고, 명시적으로 고른 값만 저장돼 있는 편이 의미도 분명하다.
        dataStore.edit { preferences ->
            preferences.remove(KEY_MOVE_SENSITIVITY)
            preferences.remove(KEY_SCROLL_PX_PER_STEP)
        }
    }

    companion object {
        /** DataStore 파일 이름 (`<앱 데이터>/datastore/gesture_settings.preferences_pb`). */
        const val PREFERENCES_NAME = "gesture_settings"

        /** 확정 스펙의 키 이름 — 저장된 값의 호환성을 깨므로 이름을 바꾸지 말 것. */
        val KEY_MOVE_SENSITIVITY = floatPreferencesKey("move_sensitivity")
        val KEY_SCROLL_PX_PER_STEP = floatPreferencesKey("scroll_px_per_step")
    }
}
