package ru.hznik.devicebridge.web

import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import ru.hznik.devicebridge.core.protocol.file.FILE_OFFER_TYPE
import ru.hznik.devicebridge.core.protocol.file.FILE_PROGRESS_TYPE
import ru.hznik.devicebridge.core.protocol.file.FILE_PROTOCOL_VERSION
import ru.hznik.devicebridge.core.protocol.file.FileOfferMessage
import ru.hznik.devicebridge.core.protocol.file.FileProgressEvent
import ru.hznik.devicebridge.core.protocol.file.FileProtocolJson
import ru.hznik.devicebridge.data.file.FileTransferCoordinator
import ru.hznik.devicebridge.data.file.FileDestinationLeaseRegistry
import ru.hznik.devicebridge.data.file.FileSourceRegistry
import ru.hznik.devicebridge.data.session.SessionEventDispatcher
import ru.hznik.devicebridge.data.session.SessionOutboundEvent
import ru.hznik.devicebridge.domain.file.FileTransferId
import ru.hznik.devicebridge.domain.file.FileTransferState
import ru.hznik.devicebridge.domain.session.BrowserSessionState

class FileSessionEventBridge(
    scope: CoroutineScope,
    private val coordinator: FileTransferCoordinator,
    private val dispatcher: SessionEventDispatcher,
    private val wallClockMs: () -> Long,
    browserSessionState: StateFlow<BrowserSessionState>? = null,
    private val destinationLeases: FileDestinationLeaseRegistry? = null,
    private val sourceRegistry: FileSourceRegistry? = null,
) : AutoCloseable {
    private val sequence = AtomicLong()
    private val job: Job = scope.launch {
        var previous = emptyMap<FileTransferId, FileTransferState>()
        coordinator.state.collect { snapshot ->
            val current = snapshot.items.associateBy { it.metadata.id }
            snapshot.items.forEach { item ->
                val prior = previous[item.metadata.id]
                if (item.phase.isTerminal && prior?.phase?.isTerminal != true) {
                    destinationLeases?.release(item.metadata.id)
                    sourceRegistry?.remove(item.metadata.id)
                }
                when {
                    prior == null -> publishOffer(item)
                    prior.phase != item.phase ||
                        prior.bytesTransferred != item.bytesTransferred ||
                        prior.speedBytesPerSecond != item.speedBytesPerSecond -> publishProgress(item)
                }
            }
            previous = current
        }
    }
    private val sessionCleanupJob: Job? = browserSessionState?.let { states ->
        scope.launch {
            var previous = states.value
            states.collect { current ->
                val previousGeneration = previous.generationId
                if (previousGeneration != null && current.generationId == previousGeneration) {
                    val currentIds = current.sessions.mapTo(mutableSetOf()) { it.id }
                    previous.sessions
                        .asSequence()
                        .filterNot { it.id in currentIds }
                        .forEach { removed ->
                            coordinator.onSessionRevoked(previousGeneration, removed.id)
                        }
                }
                previous = current
            }
        }
    }

    private suspend fun publishOffer(item: FileTransferState) {
        val payload = FileProtocolJson.encode(
            FileOfferMessage(
                protocolVersion = FILE_PROTOCOL_VERSION,
                messageId = nextMessageId(item.metadata.id),
                type = FILE_OFFER_TYPE,
                timestamp = wallClockMs(),
                batchId = "server-batch-" + sequence.get().toString(36),
                items = listOf(item.toSnapshotItem().metadata),
            ),
        )
        dispatcher.publishIfAttached(
            item.ownerSessionId,
            SessionOutboundEvent.Control(
                messageId = item.metadata.id.value,
                payload = payload,
            ),
        )
    }

    private suspend fun publishProgress(item: FileTransferState) {
        val payload = FileProtocolJson.encode(
            FileProgressEvent(
                protocolVersion = FILE_PROTOCOL_VERSION,
                messageId = nextMessageId(item.metadata.id),
                type = FILE_PROGRESS_TYPE,
                timestamp = wallClockMs(),
                transferId = item.metadata.id.value,
                status = item.toSnapshotItem().status,
                bytesTransferred = item.bytesTransferred,
                totalBytes = item.metadata.sizeBytes,
                speedBytesPerSecond = item.speedBytesPerSecond,
            ),
        )
        val event = if (item.phase.isTerminal) {
            SessionOutboundEvent.FileTerminal(item.metadata.id, payload)
        } else {
            SessionOutboundEvent.FileProgress(item.metadata.id, payload)
        }
        dispatcher.publishIfAttached(item.ownerSessionId, event)
    }

    private fun nextMessageId(transferId: FileTransferId): String =
        "server-file-${transferId.value.take(32)}-${sequence.incrementAndGet().toString(36)}"

    override fun close() {
        job.cancel()
        sessionCleanupJob?.cancel()
    }
}
