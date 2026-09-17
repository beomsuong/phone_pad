package com.example.phone_pad_app.domain.usecase

import com.example.phone_pad_app.domain.model.TrackpadEvent
import com.example.phone_pad_app.domain.repository.TrackpadRepository
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Test

class SendEventUseCaseTest {

    private val repository: TrackpadRepository = mockk(relaxed = true)
    private val useCase = SendEventUseCase(repository)

    @Test
    fun `이벤트를 그대로 repository에 위임한다 - 채널 분기는 repository 책임`() = runTest {
        val move = TrackpadEvent.Move(1.5f, -2.0f)
        val click = TrackpadEvent.Click("right")

        useCase(move)
        useCase(click)

        coVerify(exactly = 1) { repository.sendEvent(move) }
        coVerify(exactly = 1) { repository.sendEvent(click) }
    }
}
