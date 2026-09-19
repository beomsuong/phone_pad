package com.example.phone_pad_app.presentation.settings

import com.example.phone_pad_app.domain.model.GestureSettings
import com.example.phone_pad_app.domain.repository.SettingsRepository
import com.example.phone_pad_app.presentation.util.GestureConfig
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 설정 ViewModel 테스트.
 *
 * ViewModel은 값 보정을 스스로 하지 않는다 — clamp는 도메인/저장소의 책임이고, 여기서는
 * "슬라이더 콜백이 어떤 저장 호출로 번역되는지"와 "다른 필드를 건드리지 않는지"만 본다.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /** 저장소를 흉내 내는 in-memory 구현 — 저장된 값이 다음 읽기에 반영되는 것까지 재현한다. */
    private class FakeSettingsRepository(
        initial: GestureSettings = GestureSettings.DEFAULT,
    ) : SettingsRepository {
        private val state = MutableStateFlow(initial)
        override val settings: Flow<GestureSettings> = state

        var updateCount = 0
            private set
        var resetCount = 0
            private set

        override suspend fun update(settings: GestureSettings) {
            updateCount++
            // 실제 구현과 동일하게 저장 시점에 보정한다 (ViewModel이 clamp에 기대지 않는지 확인)
            state.value = settings.sanitized()
        }

        override suspend fun reset() {
            resetCount++
            state.value = GestureSettings.DEFAULT
        }

        fun current(): GestureSettings = state.value
    }

    @Test
    fun `구독하면 저장된 값을 로드하고 isLoaded가 켜진다`() = runTest(dispatcher) {
        val repository = FakeSettingsRepository(GestureSettings(2f, 60f))
        val viewModel = SettingsViewModel(repository)

        // WhileSubscribed라서 구독자가 있어야 값이 흐른다 (화면이 붙은 상태를 흉내)
        assertFalse("구독 전에는 아직 로드되지 않은 기본값이다", viewModel.uiState.value.isLoaded)
        val collectJob = backgroundScope.launch(dispatcher) { viewModel.uiState.collect { } }

        assertTrue(viewModel.uiState.value.isLoaded)
        assertEquals(2f, viewModel.uiState.value.settings.moveSensitivity, 0.0001f)
        assertEquals(60f, viewModel.uiState.value.settings.scrollPxPerStep, 0.0001f)

        collectJob.cancel()
    }

    @Test
    fun `setMoveSensitivity는 나머지 값을 건드리지 않고 저장한다`() = runTest(dispatcher) {
        val repository = FakeSettingsRepository(GestureSettings(1.5f, 60f))
        val viewModel = SettingsViewModel(repository)

        viewModel.setMoveSensitivity(3f)

        assertEquals(1, repository.updateCount)
        assertEquals(3f, repository.current().moveSensitivity, 0.0001f)
        assertEquals("다른 필드가 기본값으로 리셋되면 안 된다", 60f, repository.current().scrollPxPerStep, 0.0001f)
    }

    @Test
    fun `setScrollPxPerStep은 나머지 값을 건드리지 않고 저장한다`() = runTest(dispatcher) {
        val repository = FakeSettingsRepository(GestureSettings(3f, 40f))
        val viewModel = SettingsViewModel(repository)

        viewModel.setScrollPxPerStep(70f)

        assertEquals(70f, repository.current().scrollPxPerStep, 0.0001f)
        assertEquals(3f, repository.current().moveSensitivity, 0.0001f)
    }

    @Test
    fun `연속 변경은 직전 저장 결과 위에 쌓인다`() = runTest(dispatcher) {
        // 화면이 구독 중이 아니어도(uiState가 멈춰 있어도) 저장은 항상 최신 저장값 기준이어야 한다
        val repository = FakeSettingsRepository()
        val viewModel = SettingsViewModel(repository)

        viewModel.setMoveSensitivity(2f)
        viewModel.setScrollPxPerStep(90f)

        assertEquals(GestureSettings(moveSensitivity = 2f, scrollPxPerStep = 90f), repository.current())
    }

    @Test
    fun `범위 밖 값은 저장소 보정을 거쳐 유효한 값이 된다`() = runTest(dispatcher) {
        val repository = FakeSettingsRepository()
        val viewModel = SettingsViewModel(repository)

        viewModel.setMoveSensitivity(Float.MAX_VALUE)

        assertEquals(
            GestureConfig.MOVE_SENSITIVITY_MAX,
            repository.current().moveSensitivity,
            0.0001f,
        )
    }

    @Test
    fun `resetToDefaults는 저장소의 reset을 호출하고 기본값으로 되돌린다`() = runTest(dispatcher) {
        val repository = FakeSettingsRepository(GestureSettings(3.5f, 95f))
        val viewModel = SettingsViewModel(repository)

        viewModel.resetToDefaults()

        assertEquals(1, repository.resetCount)
        assertEquals(0, repository.updateCount)
        assertEquals(GestureSettings.DEFAULT, repository.current())
    }

    @Test
    fun `MockK 검증 - 슬라이더 콜백은 정확히 한 번의 update로 번역된다`() = runTest(dispatcher) {
        val repository: SettingsRepository = mockk(relaxed = true)
        every { repository.settings } returns flowOf(GestureSettings(1.5f, 40f))
        coEvery { repository.update(any()) } returns Unit
        val viewModel = SettingsViewModel(repository)

        viewModel.setMoveSensitivity(2.5f)

        coVerify(exactly = 1) {
            repository.update(GestureSettings(moveSensitivity = 2.5f, scrollPxPerStep = 40f))
        }
        coVerify(exactly = 0) { repository.reset() }
    }
}
