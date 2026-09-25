package ru.hznik.devicebridge.data.history

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import ru.hznik.devicebridge.core.text.sha256Hex
import ru.hznik.devicebridge.domain.history.HistoryOperationId
import ru.hznik.devicebridge.domain.history.HistoryPersistenceEvent
import ru.hznik.devicebridge.domain.history.HistoryRecord
import ru.hznik.devicebridge.domain.session.BrowserSessionId
import ru.hznik.devicebridge.domain.session.ServerGenerationId

internal fun historyOperationId(
    generationId: ServerGenerationId,
    sessionId: BrowserSessionId,
    itemId: String,
): HistoryOperationId {
    val source = buildString {
        append(generationId.value)
        append(':')
        append(sessionId.value)
        append(':')
        append(itemId)
    }
    return HistoryOperationId(source.sha256Hex())
}

internal fun CoroutineScope.persistBestEffort(
    record: HistoryRecord,
    failureReporter: HistoryPersistenceFailureReporter,
    write: suspend (HistoryRecord) -> Unit,
) {
    launch {
        try {
            write(record)
        } catch (failure: CancellationException) {
            throw failure
        } catch (_: Throwable) {
            failureReporter.report(
                HistoryPersistenceEvent.WriteFailed(record.kind),
            )
        }
    }
}
