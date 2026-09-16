package com.example.phone_pad_app.domain.usecase

import com.example.phone_pad_app.domain.model.TrackpadEvent
import com.example.phone_pad_app.domain.repository.TrackpadRepository
import javax.inject.Inject

class SendEventUseCase @Inject constructor(
    private val repository: TrackpadRepository
) {
    suspend operator fun invoke(event: TrackpadEvent) = repository.sendEvent(event)
}
