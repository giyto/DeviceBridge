package ru.hznik.devicebridge.domain.history

sealed interface HistoryPersistenceEvent {
    data class WriteFailed(
        val kind: HistoryKind,
    ) : HistoryPersistenceEvent
}
