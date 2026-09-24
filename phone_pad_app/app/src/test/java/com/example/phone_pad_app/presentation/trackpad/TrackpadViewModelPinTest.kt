package com.example.phone_pad_app.presentation.trackpad

import com.example.phone_pad_app.domain.model.ConnectionState
import com.example.phone_pad_app.domain.model.DiscoveredServer
import com.example.phone_pad_app.domain.repository.TrackpadRepository
import com.example.phone_pad_app.domain.usecase.DiscoverServersUseCase
import com.example.phone_pad_app.domain.usecase.SendEventUseCase
import com.example.phone_pad_app.presentation.util.GestureConfig
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * PIN 입력 정책 (Phase 5 — 기본 켜짐).
 *
 * 고정하는 계약 네 가지:
 * 1. PIN이 비어 있으면 **연결을 시작하지 않는다** — 빈 PIN은 서버의 브루트포스 카운터만 올린다.
 * 2. 입력한 PIN이 그대로 repository로 전달된다(가공/검증 없음, 공백만 제거).
 * 3. 자동 탐색으로 서버를 골라도 PIN은 채워지지 않는다(탐색 응답에 PIN이 없다).
 * 4. PIN은 `hostInput`과 대칭으로 `uiState`에만 살아 있다(영속화 경로가 없다).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TrackpadViewModelPinTest {

    private val dispatcher = UnconfinedTestDispatcher()

    private val repository: TrackpadRepository = mockk(relaxed = true)
    private val sendEventUseCase: SendEventUseCase = mockk(relaxed = true)
    private val discoverServersUseCase: DiscoverServersUseCase = mockk(relaxed = true)

    private lateinit var viewModel: TrackpadViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        every { repository.connectionState } returns MutableStateFlow(ConnectionState.Disconnected)
        viewModel = TrackpadViewModel(sendEventUseCase, repository, discoverServersUseCase)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /** uiState는 WhileSubscribed 공유 플로우라 구독자가 있어야 값이 갱신된다. */
    private fun CoroutineScope.subscribe() = launch(dispatcher) { viewModel.uiState.collect {} }

    @Test
    fun `PIN이 비어 있으면 연결하지 않는다`() = runTest(dispatcher) {
        viewModel.onHostInputChange(HOST)

        viewModel.connect()
        advanceUntilIdle()

        coVerify(exactly = 0) { repository.connect(any(), any(), any()) }
    }

    @Test
    fun `공백만 입력한 PIN도 빈 값으로 본다`() = runTest(dispatcher) {
        viewModel.onHostInputChange(HOST)
        viewModel.onPinInputChange("   ")

        viewModel.connect()
        advanceUntilIdle()

        coVerify(exactly = 0) { repository.connect(any(), any(), any()) }
    }

    @Test
    fun `host와 PIN이 모두 있으면 연결한다`() = runTest(dispatcher) {
        viewModel.onHostInputChange(HOST)
        viewModel.onPinInputChange(PIN)

        viewModel.connect()
        advanceUntilIdle()

        coVerify(exactly = 1) { repository.connect(HOST, GestureConfig.DEFAULT_PORT, PIN) }
    }

    @Test
    fun `PIN은 가공되지 않고 그대로 전달된다`() = runTest(dispatcher) {
        // 길이·숫자 여부를 앱이 판정하지 않는다 — 최종 판정자는 서버다(확정 스펙).
        viewModel.onHostInputChange(HOST)
        viewModel.onPinInputChange("00042")

        viewModel.connect()
        advanceUntilIdle()

        coVerify(exactly = 1) { repository.connect(HOST, GestureConfig.DEFAULT_PORT, "00042") }
    }

    @Test
    fun `PIN 앞뒤 공백은 제거되어 전달된다`() = runTest(dispatcher) {
        viewModel.onHostInputChange(HOST)
        viewModel.onPinInputChange(" $PIN ")

        viewModel.connect()
        advanceUntilIdle()

        coVerify(exactly = 1) { repository.connect(HOST, GestureConfig.DEFAULT_PORT, PIN) }
    }

    @Test
    fun `onPinInputChange가 uiState에 반영된다`() = runTest(dispatcher) {
        val job = subscribe()

        viewModel.onPinInputChange(PIN)

        assertEquals(PIN, viewModel.uiState.value.pinInput)
        job.cancel()
    }

    @Test
    fun `PIN 초기값은 빈 문자열이다`() = runTest(dispatcher) {
        val job = subscribe()

        assertEquals("", viewModel.uiState.value.pinInput)
        job.cancel()
    }

    @Test
    fun `서버를 골라도 PIN은 채워지지 않는다`() = runTest(dispatcher) {
        // 탐색 응답에는 PIN이 없고(있으면 인증이 무의미해진다) 앞으로도 넣지 않는다.
        val job = subscribe()

        viewModel.selectServer(DiscoveredServer(name = "MY-PC", host = HOST, port = 9000))

        assertEquals(HOST, viewModel.uiState.value.hostInput)
        assertEquals("", viewModel.uiState.value.pinInput)
        job.cancel()
    }

    @Test
    fun `host를 고쳐도 입력한 PIN은 유지된다`() = runTest(dispatcher) {
        // 포트와 달리 PIN은 "선택한 그 서버"에 딸린 값이 아니므로 무효화할 이유가 없다.
        val job = subscribe()
        viewModel.onPinInputChange(PIN)

        viewModel.onHostInputChange(HOST)

        assertEquals(PIN, viewModel.uiState.value.pinInput)
        job.cancel()
    }

    @Test
    fun `PIN만 입력하고 host가 비면 연결하지 않는다`() = runTest(dispatcher) {
        viewModel.onPinInputChange(PIN)

        viewModel.connect()
        advanceUntilIdle()

        coVerify(exactly = 0) { repository.connect(any(), any(), any()) }
    }

    private companion object {
        const val HOST = "192.168.0.10"
        const val PIN = "483920"
    }
}
