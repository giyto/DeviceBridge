package ru.hznik.devicebridge.domain.usecase

import kotlinx.coroutines.flow.StateFlow
import ru.hznik.devicebridge.domain.repository.TextTransferRepository
import ru.hznik.devicebridge.domain.text.SendTextRequest
import ru.hznik.devicebridge.domain.text.TextMessageId
import ru.hznik.devicebridge.domain.text.TextTransferResult
import ru.hznik.devicebridge.domain.text.TextTransferState

class ObserveTextTransfersUseCase(
    private val repository: TextTransferRepository,
) {
    operator fun invoke(): StateFlow<TextTransferState> = repository.state
}

class SendTextToBrowserUseCase(
    private val repository: TextTransferRepository,
) {
    suspend operator fun invoke(request: SendTextRequest): TextTransferResult =
        repository.send(request)
}

class RetryTextTransferUseCase(
    private val repository: TextTransferRepository,
) {
    suspend operator fun invoke(messageId: TextMessageId): TextTransferResult =
        repository.retry(messageId)
}
