package ru.hznik.devicebridge.domain.usecase

import ru.hznik.devicebridge.domain.history.HistoryFilter
import ru.hznik.devicebridge.domain.history.HistoryRecordId
import ru.hznik.devicebridge.domain.repository.HistoryRepository

class ObserveHistoryUseCase(
    private val repository: HistoryRepository,
) {
    operator fun invoke(filter: HistoryFilter = HistoryFilter()) =
        repository.observe(filter)
}

class DeleteHistoryRecordUseCase(
    private val repository: HistoryRepository,
) {
    suspend operator fun invoke(recordId: HistoryRecordId): Boolean =
        repository.delete(recordId)
}

class ClearHistoryUseCase(
    private val repository: HistoryRepository,
) {
    suspend operator fun invoke(): Int = repository.clear()
}
