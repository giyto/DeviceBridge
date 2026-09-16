package ru.hznik.devicebridge.data.file

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ru.hznik.devicebridge.domain.file.FileTransferDirection
import ru.hznik.devicebridge.domain.file.FileTransferEvent
import ru.hznik.devicebridge.domain.file.FileTransferId
import ru.hznik.devicebridge.domain.file.FileTransferPhase
import ru.hznik.devicebridge.domain.file.FileTransferReducer
import ru.hznik.devicebridge.domain.file.FileTransferSnapshot
import ru.hznik.devicebridge.domain.file.FileTransferState

class FileTransferScheduler {
    private val mutex = Mutex()
    private val items = mutableListOf<FileTransferState>()
    private val mutableState = MutableStateFlow(FileTransferSnapshot(emptyList()))

    val state: StateFlow<FileTransferSnapshot> = mutableState.asStateFlow()

    suspend fun enqueue(newItems: List<FileTransferState>) = mutex.withLock {
        val existingIds = items.mapTo(mutableSetOf()) { it.metadata.id }
        require(newItems.all { it.phase == FileTransferPhase.QUEUED })
        require(newItems.map { it.metadata.id }.distinct().size == newItems.size)
        require(newItems.none { it.metadata.id in existingIds })
        items += newItems
        promote(FileTransferDirection.ANDROID_TO_BROWSER)
        promote(FileTransferDirection.BROWSER_TO_ANDROID)
        publish()
    }

    suspend fun transition(
        transferId: FileTransferId,
        event: FileTransferEvent,
    ): FileTransferState? = mutex.withLock {
        val index = items.indexOfFirst { it.metadata.id == transferId }
        if (index < 0) return@withLock null
        val current = items[index]
        val updated = FileTransferReducer.reduce(current, event)
        if (updated !== current) {
            items[index] = updated
            if (updated.phase.isTerminal) {
                promote(updated.metadata.direction)
            }
            publish()
        }
        updated
    }

    suspend fun retry(transferId: FileTransferId): FileTransferState? = mutex.withLock {
        val index = items.indexOfFirst { it.metadata.id == transferId }
        if (index < 0) return@withLock null
        val current = items[index]
        if (
            current.phase != FileTransferPhase.FAILED &&
            current.phase != FileTransferPhase.CANCELLED
        ) {
            return@withLock current
        }
        items[index] = FileTransferState.queued(
            generationId = current.generationId,
            ownerSessionId = current.ownerSessionId,
            metadata = current.metadata,
        )
        promote(current.metadata.direction)
        publish()
        items.first { it.metadata.id == transferId }
    }

    suspend fun clear() = mutex.withLock {
        items.clear()
        publish()
    }

    private fun promote(direction: FileTransferDirection) {
        val hasActive = items.any {
            it.metadata.direction == direction &&
                it.phase in ACTIVE_PHASES
        }
        if (hasActive) return
        val queuedIndex = items.indexOfFirst {
            it.metadata.direction == direction &&
                it.phase == FileTransferPhase.QUEUED
        }
        if (queuedIndex >= 0) {
            items[queuedIndex] = FileTransferReducer.reduce(
                items[queuedIndex],
                FileTransferEvent.Connecting,
            )
        }
    }

    private fun publish() {
        mutableState.value = FileTransferSnapshot(items.toList())
    }

    private companion object {
        val ACTIVE_PHASES = setOf(
            FileTransferPhase.CONNECTING,
            FileTransferPhase.TRANSFERRING,
            FileTransferPhase.VERIFYING,
        )
    }
}
