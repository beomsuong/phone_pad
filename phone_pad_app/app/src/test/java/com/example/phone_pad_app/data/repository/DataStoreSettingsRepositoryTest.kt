package com.example.phone_pad_app.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import com.example.phone_pad_app.domain.model.GestureSettings
import com.example.phone_pad_app.presentation.util.GestureConfig
import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * DataStore 구현의 **실제 파일 왕복** 테스트.
 *
 * MockK로 `DataStore`를 흉내 내지 않고 임시 파일 기반의 진짜 인스턴스를 쓴다 — 이 클래스에서
 * 틀릴 수 있는 것은 "키 이름이 맞는지, 저장한 값이 진짜 디스크를 거쳐 돌아오는지, 없는 값이
 * 기본값으로 떨어지는지"인데 mock을 끼우면 전부 검증 대상에서 빠져 버린다.
 * `PreferenceDataStoreFactory`는 순수 JVM 모듈에 있어 Robolectric 없이 그대로 쓸 수 있다.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DataStoreSettingsRepositoryTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    /** 테스트가 만든 DataStore 스코프 — 끝나면 전부 정리해야 파일 잠금이 풀린다. */
    private val jobs = mutableListOf<CompletableJob>()

    private val file: File
        get() = File(tempFolder.root, "gesture_settings.preferences_pb")

    @After
    fun tearDown() {
        jobs.forEach { it.cancel() }
    }

    /**
     * 같은 파일을 보는 DataStore를 새로 만든다.
     *
     * DataStore는 같은 파일에 인스턴스가 동시에 둘 존재하면 예외를 던지므로, 앞선 인스턴스의
     * 스코프를 [CompletableJob.cancelAndJoin]으로 완전히 끝낸 뒤에 호출해야 한다 —
     * 그 시점에 내부 활성 파일 목록에서 빠진다. 덕분에 "프로세스를 재시작한 것과 같은"
     * 진짜 영속성 검증이 가능하다.
     */
    private fun newStore(): Pair<DataStore<Preferences>, DataStoreSettingsRepository> {
        val job = Job().also { jobs += it }
        val store = PreferenceDataStoreFactory.create(
            scope = CoroutineScope(Dispatchers.IO + job),
            produceFile = { file },
        )
        return store to DataStoreSettingsRepository(store)
    }

    @Test
    fun `저장된 값이 없으면 기본값을 방출한다`() = runTest {
        val (_, repository) = newStore()

        assertEquals(GestureSettings.DEFAULT, repository.settings.first())
    }

    @Test
    fun `저장한 값은 새 DataStore 인스턴스에서도 그대로 읽힌다`() = runTest {
        val expected = GestureSettings(moveSensitivity = 2.5f, scrollPxPerStep = 60f)

        val (_, writer) = newStore()
        writer.update(expected)
        jobs.removeLast().cancelAndJoin() // 앱이 종료된 상황을 흉내 낸다

        val (_, reader) = newStore()
        assertEquals(expected, reader.settings.first())
    }

    @Test
    fun `한쪽 값만 바꿔도 다른 값은 보존된다`() = runTest {
        val store = InMemoryPreferencesDataStore()
        val repository = DataStoreSettingsRepository(store)
        repository.update(GestureSettings(moveSensitivity = 3f, scrollPxPerStep = 80f))

        repository.update(repository.settings.first().copy(moveSensitivity = 1f))

        val loaded = repository.settings.first()
        assertEquals(1f, loaded.moveSensitivity, 0.0001f)
        assertEquals(80f, loaded.scrollPxPerStep, 0.0001f)
    }

    @Test
    fun `범위 밖 값이 저장돼 있어도 clamp 되어 읽힌다`() = runTest {
        // 과거 버전이 저장한 값이나 손상된 파일에서 복구된 값을 흉내 내기 위해
        // 리포지토리를 거치지 않고 키에 직접 써 넣는다.
        val (store, repository) = newStore()
        store.edit { preferences ->
            preferences[DataStoreSettingsRepository.KEY_MOVE_SENSITIVITY] = 999f
            preferences[DataStoreSettingsRepository.KEY_SCROLL_PX_PER_STEP] = 1f
        }

        val loaded = repository.settings.first()
        assertEquals(GestureConfig.MOVE_SENSITIVITY_MAX, loaded.moveSensitivity, 0.0001f)
        assertEquals(GestureConfig.SCROLL_PX_PER_STEP_MIN, loaded.scrollPxPerStep, 0.0001f)
    }

    @Test
    fun `NaN이 저장돼 있으면 기본값으로 읽힌다`() = runTest {
        val (store, repository) = newStore()
        store.edit { preferences ->
            preferences[DataStoreSettingsRepository.KEY_MOVE_SENSITIVITY] = Float.NaN
            preferences[DataStoreSettingsRepository.KEY_SCROLL_PX_PER_STEP] = Float.NaN
        }

        assertEquals(GestureSettings.DEFAULT, repository.settings.first())
    }

    @Test
    fun `범위 밖 값을 저장하려 하면 보정된 값이 디스크에 남는다`() = runTest {
        val (store, repository) = newStore()

        repository.update(GestureSettings(moveSensitivity = 99f, scrollPxPerStep = 1f))

        val raw = store.data.first()
        assertEquals(
            GestureConfig.MOVE_SENSITIVITY_MAX,
            raw[DataStoreSettingsRepository.KEY_MOVE_SENSITIVITY]!!,
            0.0001f,
        )
        assertEquals(
            GestureConfig.SCROLL_PX_PER_STEP_MIN,
            raw[DataStoreSettingsRepository.KEY_SCROLL_PX_PER_STEP]!!,
            0.0001f,
        )
    }

    @Test
    fun `reset 후에는 기본값이 읽히고 저장된 키도 지워진다`() = runTest {
        val store = InMemoryPreferencesDataStore()
        val repository = DataStoreSettingsRepository(store)
        repository.update(GestureSettings(moveSensitivity = 3f, scrollPxPerStep = 90f))

        repository.reset()

        assertEquals(GestureSettings.DEFAULT, repository.settings.first())
        // 기본값을 "써넣는" 게 아니라 지우는 방식이어야 나중에 기본 상수가 바뀌었을 때
        // 복원한 사용자가 새 기본값을 따라간다.
        val raw = store.data.first()
        assertNull(raw[DataStoreSettingsRepository.KEY_MOVE_SENSITIVITY])
        assertNull(raw[DataStoreSettingsRepository.KEY_SCROLL_PX_PER_STEP])
    }

    @Test
    fun `reset은 저장된 값이 없어도 안전하다`() = runTest {
        val (_, repository) = newStore()

        repository.reset()

        assertEquals(GestureSettings.DEFAULT, repository.settings.first())
    }

    /**
     * 같은 파일에 **두 번 이상 쓰는** 시나리오 전용 대역.
     *
     * DataStore 1.0.0은 임시 파일에 쓴 뒤 `File.renameTo`로 갈아끼우는데, Windows의 `renameTo`는
     * 대상 파일이 이미 있으면 실패한다(POSIX `rename`과 달리 덮어쓰기가 안 됨). 그래서 이 JVM
     * 단위 테스트 환경에서는 "이미 저장된 파일에 다시 쓰기"가 항상 `IOException`으로 끝난다 —
     * 라이브러리/플랫폼 제약이며 실제 배포 대상인 Android(리눅스)에서는 정상 동작한다.
     *
     * 파일 왕복 자체는 위의 실파일 테스트들이 검증하므로, 여기서는 같은 `DataStore` 계약을
     * 메모리로 구현해 **리포지토리의 다중 쓰기 로직**(필드 독립성, reset의 키 제거)만 본다.
     */
    private class InMemoryPreferencesDataStore : DataStore<Preferences> {
        private val state = MutableStateFlow<Preferences>(emptyPreferences())
        override val data: Flow<Preferences> = state

        override suspend fun updateData(
            transform: suspend (t: Preferences) -> Preferences,
        ): Preferences {
            val updated = transform(state.value)
            state.value = updated
            return updated
        }
    }

    @Test
    fun `키 이름은 확정 스펙과 일치한다`() {
        // 이름이 바뀌면 이미 설치된 앱의 저장값이 조용히 무시되고 기본값으로 돌아간다.
        assertEquals("move_sensitivity", DataStoreSettingsRepository.KEY_MOVE_SENSITIVITY.name)
        assertEquals("scroll_px_per_step", DataStoreSettingsRepository.KEY_SCROLL_PX_PER_STEP.name)
    }
}
