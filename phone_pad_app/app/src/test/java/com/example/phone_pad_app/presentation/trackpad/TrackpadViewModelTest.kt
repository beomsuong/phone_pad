package com.example.phone_pad_app.presentation.trackpad

import com.example.phone_pad_app.domain.model.ConnectionState
import com.example.phone_pad_app.domain.model.TrackpadEvent
import com.example.phone_pad_app.domain.repository.TrackpadRepository
import com.example.phone_pad_app.domain.usecase.SendEventUseCase
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * ViewModel은 네트워크 계층을 직접 호출하지 않고 [SendEventUseCase]에만 의존한다.
 * 여기서는 "어떤 제스처 콜백이 어떤 [TrackpadEvent]로 번역되는지"만 검증한다 —
 * 채널(TCP/UDP) 선택은 repository 책임이므로 이 계층의 관심사가 아니다.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TrackpadViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()

    private val repository: TrackpadRepository = mockk(relaxed = true)
    private val sendEventUseCase: SendEventUseCase = mockk(relaxed = true)

    private lateinit var viewModel: TrackpadViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        every { repository.connectionState } returns MutableStateFlow(ConnectionState.Disconnected)
        viewModel = TrackpadViewModel(sendEventUseCase, repository)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `sendRightClick은 button이 right인 CLICK 이벤트를 UseCase로 보낸다`() {
        viewModel.sendRightClick()

        coVerify(exactly = 1) { sendEventUseCase(TrackpadEvent.Click(button = "right")) }
    }

    @Test
    fun `sendClick은 여전히 button이 left인 CLICK 이벤트를 보낸다`() {
        viewModel.sendClick()

        coVerify(exactly = 1) { sendEventUseCase(TrackpadEvent.Click(button = "left")) }
        coVerify(exactly = 0) { sendEventUseCase(TrackpadEvent.Click(button = "right")) }
    }

    @Test
    fun `좌우 클릭은 서로 다른 이벤트로 구분되어 전달된다`() {
        viewModel.sendClick()
        viewModel.sendRightClick()
        viewModel.sendRightClick()

        coVerify(exactly = 1) { sendEventUseCase(TrackpadEvent.Click(button = "left")) }
        coVerify(exactly = 2) { sendEventUseCase(TrackpadEvent.Click(button = "right")) }
    }

    @Test
    fun `sendDoubleClick은 button이 left인 DOUBLE_CLICK 이벤트를 보낸다`() {
        viewModel.sendDoubleClick()

        coVerify(exactly = 1) { sendEventUseCase(TrackpadEvent.DoubleClick(button = "left")) }
    }

    @Test
    fun `sendDoubleClick은 CLICK 이벤트를 보내지 않는다`() {
        // 더블탭은 CLICK 두 개가 아니라 DOUBLE_CLICK 하나로 번역된다 (확정 스펙)
        viewModel.sendDoubleClick()

        coVerify(exactly = 0) { sendEventUseCase(ofType(TrackpadEvent.Click::class)) }
    }

    @Test
    fun `sendClick과 sendDoubleClick은 서로 다른 이벤트 타입으로 구분된다`() {
        viewModel.sendClick()
        viewModel.sendDoubleClick()

        coVerify(exactly = 1) { sendEventUseCase(TrackpadEvent.Click(button = "left")) }
        coVerify(exactly = 1) { sendEventUseCase(TrackpadEvent.DoubleClick(button = "left")) }
    }

    @Test
    fun `sendDragStart는 DRAG_START 이벤트를 보낸다`() {
        viewModel.sendDragStart()

        coVerify(exactly = 1) { sendEventUseCase(TrackpadEvent.DragStart) }
        coVerify(exactly = 0) { sendEventUseCase(TrackpadEvent.DragEnd) }
        // 드래그는 클릭 계열로 번역되지 않는다 — 버튼을 누른 채 유지하는 별개의 상태 전이다
        coVerify(exactly = 0) { sendEventUseCase(ofType(TrackpadEvent.Click::class)) }
    }

    @Test
    fun `sendDragEnd는 DRAG_END 이벤트를 보낸다`() {
        viewModel.sendDragEnd()

        coVerify(exactly = 1) { sendEventUseCase(TrackpadEvent.DragEnd) }
        coVerify(exactly = 0) { sendEventUseCase(TrackpadEvent.DragStart) }
    }

    @Test
    fun `드래그 중 이동은 기존 MOVE 경로를 그대로 쓴다`() {
        // 확정 스펙: DRAG 전용 이동 이벤트는 없다. START → MOVE → END 순서만 유지하면 된다.
        viewModel.sendDragStart()
        viewModel.sendMove(3f, -4f)
        viewModel.sendDragEnd()

        coVerify(exactly = 1) { sendEventUseCase(TrackpadEvent.DragStart) }
        coVerify(exactly = 1) { sendEventUseCase(TrackpadEvent.Move(3f, -4f)) }
        coVerify(exactly = 1) { sendEventUseCase(TrackpadEvent.DragEnd) }
    }

    @Test
    fun `sendMove는 MOVE 이벤트를 보낸다 - 클릭 경로와 섞이지 않는다`() {
        viewModel.sendMove(1.5f, -2f)

        coVerify(exactly = 1) { sendEventUseCase(TrackpadEvent.Move(1.5f, -2f)) }
        coVerify(exactly = 0) { sendEventUseCase(ofType(TrackpadEvent.Click::class)) }
    }

    @Test
    fun `sendScroll은 정수 스텝 그대로 SCROLL 이벤트를 보낸다`() {
        viewModel.sendScroll(0, -3)

        coVerify(exactly = 1) { sendEventUseCase(TrackpadEvent.Scroll(dx = 0, dy = -3)) }
        coVerify(exactly = 0) { sendEventUseCase(ofType(TrackpadEvent.Click::class)) }
        coVerify(exactly = 0) { sendEventUseCase(ofType(TrackpadEvent.Move::class)) }
    }

    @Test
    fun `트래커의 ScrollDelta가 그대로 도메인 이벤트 필드로 옮겨진다`() {
        // MultiTouchGestureTracker → TrackpadScreen → ViewModel 사이에서 축이 뒤바뀌지 않는지 고정
        val delta = ScrollDelta(dx = 2, dy = -5)
        viewModel.sendScroll(delta.dx, delta.dy)

        coVerify(exactly = 1) { sendEventUseCase(TrackpadEvent.Scroll(dx = 2, dy = -5)) }
    }

    @Test
    fun `제스처 판정기가 돌려주는 버튼 문자열과 도메인 이벤트의 button 값이 같다`() {
        // MultiTouchGestureTracker의 판정 결과 → TrackpadScreen 분기 → ViewModel 전송까지
        // 같은 어휘를 쓰는지 고정한다 (AGENTS.md 섹션 4)
        assertEquals(
            MultiTouchGestureTracker.BUTTON_LEFT,
            TrackpadEvent.Click(MultiTouchGestureTracker.BUTTON_LEFT).button,
        )
        assertEquals(
            MultiTouchGestureTracker.BUTTON_RIGHT,
            TrackpadEvent.Click(MultiTouchGestureTracker.BUTTON_RIGHT).button,
        )
        assertEquals("left", TrackpadEvent.Click().button)
    }
}
