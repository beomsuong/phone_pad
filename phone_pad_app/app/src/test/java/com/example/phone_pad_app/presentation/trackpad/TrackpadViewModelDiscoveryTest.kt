package com.example.phone_pad_app.presentation.trackpad

import com.example.phone_pad_app.domain.model.ConnectionState
import com.example.phone_pad_app.domain.model.DiscoveredServer
import com.example.phone_pad_app.domain.model.DiscoveryState
import com.example.phone_pad_app.domain.repository.TrackpadRepository
import com.example.phone_pad_app.domain.usecase.DiscoverServersUseCase
import com.example.phone_pad_app.domain.usecase.SendEventUseCase
import com.example.phone_pad_app.presentation.util.GestureConfig
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * "서버 찾기"의 ViewModel 정책 (확정 스펙).
 *
 * 고정하는 계약 네 가지:
 * 1. 선택은 **입력란만 채운다** — 절대 자동으로 연결하지 않는다.
 * 2. 사용자가 호스트를 직접 고치면 포트는 기본값으로 되돌아간다(비표준 포트 누수 방지).
 * 3. 탐색 중 재호출은 무시한다(창 리셋으로 "영원히 찾는 중"이 되지 않도록).
 * 4. 연결을 시작하거나 취소하면 진행 중인 탐색을 끊는다.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TrackpadViewModelDiscoveryTest {

    private val dispatcher = UnconfinedTestDispatcher()

    private val repository: TrackpadRepository = mockk(relaxed = true)
    private val sendEventUseCase: SendEventUseCase = mockk(relaxed = true)
    private val discoverServersUseCase: DiscoverServersUseCase = mockk()

    private lateinit var viewModel: TrackpadViewModel

    private val found = DiscoveredServer(name = "MY-PC", host = "192.168.0.11", port = 9000)
    private val oddPortServer = DiscoveredServer(name = "ODD", host = "192.168.0.12", port = 9100)

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

    // --- 탐색 결과 상태 ---------------------------------------------------------------

    @Test
    fun `탐색은 Searching을 거쳐 Found로 간다`() = runTest(dispatcher) {
        val job = subscribe()
        coEvery { discoverServersUseCase() } coAnswers {
            assertEquals(DiscoveryState.Searching, viewModel.uiState.value.discovery)
            listOf(found)
        }

        viewModel.startDiscovery()
        advanceUntilIdle()

        assertEquals(DiscoveryState.Found(listOf(found)), viewModel.uiState.value.discovery)
        job.cancel()
    }

    @Test
    fun `결과가 없으면 NotFound다`() = runTest(dispatcher) {
        val job = subscribe()
        coEvery { discoverServersUseCase() } returns emptyList()

        viewModel.startDiscovery()
        advanceUntilIdle()

        assertEquals(DiscoveryState.NotFound, viewModel.uiState.value.discovery)
        job.cancel()
    }

    @Test
    fun `탐색이 예외로 끝나도 화면이 Searching에 갇히지 않는다`() = runTest(dispatcher) {
        val job = subscribe()
        coEvery { discoverServersUseCase() } throws IllegalStateException("boom")

        viewModel.startDiscovery()
        advanceUntilIdle()

        assertEquals(DiscoveryState.NotFound, viewModel.uiState.value.discovery)
        job.cancel()
    }

    @Test
    fun `탐색 중 재호출은 무시한다`() = runTest(dispatcher) {
        coEvery { discoverServersUseCase() } coAnswers {
            delay(1_000)
            listOf(found)
        }

        viewModel.startDiscovery()
        viewModel.startDiscovery()
        viewModel.startDiscovery()
        advanceUntilIdle()

        coVerify(exactly = 1) { discoverServersUseCase() }
    }

    @Test
    fun `끝난 탐색은 다시 시작할 수 있다`() = runTest(dispatcher) {
        coEvery { discoverServersUseCase() } returns emptyList()

        viewModel.startDiscovery()
        advanceUntilIdle()
        viewModel.startDiscovery()
        advanceUntilIdle()

        coVerify(exactly = 2) { discoverServersUseCase() }
    }

    // --- 선택 -------------------------------------------------------------------------

    @Test
    fun `서버를 고르면 입력란과 포트가 채워진다`() = runTest(dispatcher) {
        val job = subscribe()

        viewModel.selectServer(oddPortServer)

        assertEquals("192.168.0.12", viewModel.uiState.value.hostInput)
        assertEquals(9100, viewModel.uiState.value.port)
        job.cancel()
    }

    @Test
    fun `서버를 골라도 자동으로 연결하지 않는다`() = runTest(dispatcher) {
        viewModel.selectServer(found)
        advanceUntilIdle()

        coVerify(exactly = 0) { repository.connect(any(), any()) }
    }

    @Test
    fun `고른 서버의 포트로 연결한다`() = runTest(dispatcher) {
        viewModel.selectServer(oddPortServer)
        viewModel.connect()
        advanceUntilIdle()

        coVerify(exactly = 1) { repository.connect("192.168.0.12", 9100) }
    }

    @Test
    fun `사용자가 호스트를 고치면 포트는 기본값으로 돌아간다`() = runTest(dispatcher) {
        // 고른 서버의 비표준 포트가 무관한 IP로 새면 엉뚱한 곳에 붙으러 간다.
        viewModel.selectServer(oddPortServer)
        viewModel.onHostInputChange("10.0.0.5")
        viewModel.connect()
        advanceUntilIdle()

        coVerify(exactly = 1) { repository.connect("10.0.0.5", GestureConfig.DEFAULT_PORT) }
        coVerify(exactly = 0) { repository.connect(any(), 9100) }
    }

    @Test
    fun `수동 입력만으로도 기본 포트로 연결된다`() = runTest(dispatcher) {
        // 자동 탐색이 추가돼도 수동 IP 입력 경로는 그대로 fallback으로 살아 있어야 한다.
        viewModel.onHostInputChange(" 192.168.0.50 ")
        viewModel.connect()
        advanceUntilIdle()

        coVerify(exactly = 1) { repository.connect("192.168.0.50", GestureConfig.DEFAULT_PORT) }
    }

    // --- 탐색 취소 ---------------------------------------------------------------------

    @Test
    fun `연결을 시작하면 진행 중인 탐색을 끊는다`() = runTest(dispatcher) {
        val job = subscribe()
        var completed = false
        coEvery { discoverServersUseCase() } coAnswers {
            delay(1_000)
            completed = true
            listOf(found)
        }

        viewModel.startDiscovery()
        viewModel.onHostInputChange("192.168.0.99")
        viewModel.connect()
        advanceUntilIdle()

        assertEquals(DiscoveryState.Idle, viewModel.uiState.value.discovery)
        assertTrue("취소된 탐색이 끝까지 돌았다", !completed)
        job.cancel()
    }

    @Test
    fun `첫 연결을 취소해도 탐색은 되살아나지 않는다`() = runTest(dispatcher) {
        val job = subscribe()
        coEvery { discoverServersUseCase() } coAnswers {
            delay(1_000)
            listOf(found)
        }

        viewModel.startDiscovery()
        viewModel.cancelConnect()
        advanceUntilIdle()

        assertEquals(DiscoveryState.Idle, viewModel.uiState.value.discovery)
        coVerify(exactly = 1) { repository.cancelConnect() }
        job.cancel()
    }

    @Test
    fun `호스트가 비어 있으면 연결하지 않고 탐색 상태도 건드리지 않는다`() = runTest(dispatcher) {
        val job = subscribe()
        coEvery { discoverServersUseCase() } returns listOf(found)
        viewModel.startDiscovery()
        advanceUntilIdle()

        viewModel.connect()
        advanceUntilIdle()

        coVerify(exactly = 0) { repository.connect(any(), any()) }
        assertEquals(DiscoveryState.Found(listOf(found)), viewModel.uiState.value.discovery)
        job.cancel()
    }
}
