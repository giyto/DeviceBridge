package ru.hznik.devicebridge.domain.repository

import kotlinx.coroutines.flow.StateFlow
import ru.hznik.devicebridge.domain.text.SendTextRequest
import ru.hznik.devicebridge.domain.text.TextMessageId
import ru.hznik.devicebridge.domain.text.TextTransferResult
import ru.hznik.devicebridge.domain.text.TextTransferState

interface TextTransferRepository {
    val state: StateFlow<TextTransferState>

    suspend fun send(request: SendTextRequest): TextTransferResult

    suspend fun retry(messageId: TextMessageId): TextTransferResult
}
