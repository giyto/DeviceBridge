package ru.hznik.devicebridge.data.history

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import ru.hznik.devicebridge.domain.history.HistoryPersistenceEvent

fun interface HistoryPersistenceFailureReporter {
    suspend fun report(event: HistoryPersistenceEvent.WriteFailed)
}

class HistoryPersistenceEventBus : HistoryPersistenceFailureReporter {
    private val mutableEvents = MutableSharedFlow<HistoryPersistenceEvent>(
        extraBufferCapacity = 16,
    )

    val events: SharedFlow<HistoryPersistenceEvent> = mutableEvents.asSharedFlow()

    override suspend fun report(event: HistoryPersistenceEvent.WriteFailed) {
        mutableEvents.emit(event)
    }
}
