package ru.hznik.devicebridge.data.file

import java.security.MessageDigest
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import ru.hznik.devicebridge.domain.file.CreateFileTransfersRequest
import ru.hznik.devicebridge.domain.file.FileCommandId
import ru.hznik.devicebridge.domain.file.FileDestinationId
import ru.hznik.devicebridge.domain.file.FileTransferDirection
import ru.hznik.devicebridge.domain.file.FileTransferEvent
import ru.hznik.devicebridge.domain.file.FileTransferFailure
import ru.hznik.devicebridge.domain.file.FileTransferId
import ru.hznik.devicebridge.domain.file.FileTransferOperationResult
import ru.hznik.devicebridge.domain.file.FileTransferPhase
import ru.hznik.devicebridge.domain.file.FileTransferSnapshot
import ru.hznik.devicebridge.domain.file.FileTransferState
import ru.hznik.devicebridge.domain.file.VerifyFileTransferRequest
import ru.hznik.devicebridge.domain.repository.FileTransferRepository
import ru.hznik.devicebridge.domain.session.BrowserSessionId
import ru.hznik.devicebridge.domain.session.BrowserSessionState
import ru.hznik.devicebridge.domain.session.ServerGenerationId

fun interface FileTerminalHistoryRecorder {
    suspend fun recordTerminal(item: FileTransferState)
}

class FileTransferCoordinator(
    private val browserSessionState: () -> BrowserSessionState,
    private val maxItems: Int = 100,
    private val scheduler: FileTransferScheduler = FileTransferScheduler(),
    private val downloadGrantRegistry: DownloadGrantRegistry = DownloadGrantRegistry(
        nowEpochMillis = System::currentTimeMillis,
    ),
    private val wifiLock: FileTransferWifiLock = NoOpFileTransferWifiLock,
    private val historyRecorder: FileTerminalHistoryRecorder =
        FileTerminalHistoryRecorder { },
) : FileTransferRepository {
    private data class OperationKey(
        val generationId: ServerGenerationId,
        val sessionId: BrowserSessionId,
        val commandId: FileCommandId,
    )

    private data class StoredCommand(
        val fingerprint: String,
        val result: FileTransferOperationResult,
    )

    private val mutex = Mutex()
    private val commands = LinkedHashMap<OperationKey, StoredCommand>()
    private val destinations = LinkedHashMap<FileTransferId, FileDestinationId>()
    private val resources = LinkedHashMap<FileTransferId, FileTransferResources>()
    private var activeGenerationId: ServerGenerationId? = null

    init {
        require(maxItems > 0)
    }

    override val state: StateFlow<FileTransferSnapshot> = scheduler.state

    suspend fun activate(generationId: ServerGenerationId) {
        mutex.withLock {
            cleanupAllResources()
            wifiLock.releaseAll()
            activeGenerationId?.let { previousGeneration ->
                downloadGrantRegistry.invalidateGeneration(previousGeneration)
            }
            activeGenerationId = generationId
            commands.clear()
            destinations.clear()
            scheduler.clear()
        }
    }

    suspend fun close(generationId: ServerGenerationId) {
        mutex.withLock {
            if (activeGenerationId != generationId) return@withLock
            state.value.items
                .filterNot { item -> item.phase.isTerminal }
                .forEach { item ->
                    cleanupResources(item.metadata.id)
                    transitionWithWifiLock(item.metadata.id, FileTransferEvent.Cancelled)
                }
            downloadGrantRegistry.invalidateGeneration(generationId)
            activeGenerationId = null
            commands.clear()
            destinations.clear()
            resources.clear()
            wifiLock.releaseAll()
            scheduler.clear()
        }
    }

    override suspend fun create(
        request: CreateFileTransfersRequest,
    ): FileTransferOperationResult = mutex.withLock {
        if (activeGenerationId != request.generationId) {
            return@withLock FileTransferOperationResult.InvalidState
        }
        val ownsSession = browserSessionState().sessions.any {
            it.id == request.ownerSessionId &&
                it.generationId == request.generationId
        }
        if (!ownsSession) {
            return@withLock FileTransferOperationResult.Rejected(
                FileTransferFailure.SessionUnavailable,
            )
        }

        val key = OperationKey(
            request.generationId,
            request.ownerSessionId,
            request.commandId,
        )
        val fingerprint = request.fingerprint()
        commands[key]?.let { stored ->
            return@withLock if (stored.fingerprint == fingerprint) {
                stored.result
            } else {
                FileTransferOperationResult.Conflict
            }
        }
        if (state.value.items.size + request.files.size > maxItems) {
            return@withLock FileTransferOperationResult.Rejected(
                FileTransferFailure.CapacityReached,
            )
        }
        if (request.files.any { file -> state.value.item(file.id) != null }) {
            return@withLock FileTransferOperationResult.Conflict
        }

        val queued = request.files.map { metadata ->
            FileTransferState.queued(
                generationId = request.generationId,
                ownerSessionId = request.ownerSessionId,
                metadata = metadata,
            )
        }
        scheduler.enqueue(queued)
        val result = FileTransferOperationResult.Accepted
        commands[key] = StoredCommand(fingerprint, result)
        result
    }

    override suspend fun approve(
        transferId: FileTransferId,
        destinationId: FileDestinationId?,
    ): FileTransferOperationResult = mutex.withLock {
        val item = state.value.item(transferId) ?: return@withLock FileTransferOperationResult.NotFound
        if (!isCurrent(item)) return@withLock FileTransferOperationResult.InvalidState
        if (
            item.metadata.direction == FileTransferDirection.BROWSER_TO_ANDROID &&
            destinationId == null
        ) {
            return@withLock FileTransferOperationResult.InvalidState
        }
        destinationId?.let { destinations[transferId] = it }
        val updated = transitionWithWifiLock(transferId, FileTransferEvent.Started)
        if (updated?.phase == FileTransferPhase.TRANSFERRING) {
            FileTransferOperationResult.Accepted
        } else {
            FileTransferOperationResult.InvalidState
        }
    }

    override suspend fun cancel(
        transferId: FileTransferId,
    ): FileTransferOperationResult = mutex.withLock {
        val item = state.value.item(transferId) ?: return@withLock FileTransferOperationResult.NotFound
        if (!isCurrent(item) || item.phase.isTerminal) {
            return@withLock FileTransferOperationResult.InvalidState
        }
        downloadGrantRegistry.invalidateTransfer(item.generationId, transferId)
        cleanupResources(transferId)
        transitionWithWifiLock(transferId, FileTransferEvent.Cancelled)
        destinations.remove(transferId)
        FileTransferOperationResult.Accepted
    }

    override suspend fun retry(
        transferId: FileTransferId,
    ): FileTransferOperationResult = mutex.withLock {
        val item = state.value.item(transferId)
            ?: return@withLock FileTransferOperationResult.NotFound
        if (
            !isCurrent(item) ||
            item.phase != FileTransferPhase.FAILED &&
            item.phase != FileTransferPhase.CANCELLED
        ) {
            return@withLock FileTransferOperationResult.InvalidState
        }
        val ownsSession = browserSessionState().sessions.any { session ->
            session.id == item.ownerSessionId && session.generationId == item.generationId
        }
        if (!ownsSession) {
            return@withLock FileTransferOperationResult.Rejected(
                FileTransferFailure.SessionUnavailable,
            )
        }
        downloadGrantRegistry.invalidateTransfer(item.generationId, transferId)
        cleanupResources(transferId)
        destinations.remove(transferId)
        val retried = scheduler.retry(transferId)
        if (retried?.phase == FileTransferPhase.CONNECTING ||
            retried?.phase == FileTransferPhase.QUEUED
        ) {
            FileTransferOperationResult.Accepted
        } else {
            FileTransferOperationResult.InvalidState
        }
    }

    override suspend fun verify(
        request: VerifyFileTransferRequest,
    ): FileTransferOperationResult = mutex.withLock {
        val item = state.value.item(request.transferId)
            ?: return@withLock FileTransferOperationResult.NotFound
        if (!isCurrent(item) || item.phase != FileTransferPhase.VERIFYING) {
            return@withLock FileTransferOperationResult.InvalidState
        }
        if (
            item.metadata.sizeBytes != request.sizeBytes ||
            !item.metadata.sha256.equals(request.sha256, ignoreCase = true)
        ) {
            transitionWithWifiLock(
                request.transferId,
                FileTransferEvent.Failed(FileTransferFailure.ChecksumMismatch),
            )
            return@withLock FileTransferOperationResult.Rejected(
                FileTransferFailure.ChecksumMismatch,
            )
        }
        transitionWithWifiLock(request.transferId, FileTransferEvent.Completed)
        FileTransferOperationResult.Accepted
    }

    suspend fun attachResources(
        transferId: FileTransferId,
        transferResources: FileTransferResources,
    ): Boolean = mutex.withLock {
        val item = state.value.item(transferId) ?: return@withLock false
        if (!isCurrent(item) || item.phase.isTerminal || item.phase == FileTransferPhase.QUEUED) {
            return@withLock false
        }
        if (resources.containsKey(transferId)) return@withLock false
        resources[transferId] = transferResources
        true
    }

    suspend fun releaseResources(transferId: FileTransferId) {
        mutex.withLock { resources.remove(transferId) }
    }

    suspend fun ownedTransfer(
        generationId: ServerGenerationId,
        sessionId: BrowserSessionId,
        transferId: FileTransferId,
    ): FileTransferState? = mutex.withLock {
        state.value.item(transferId)?.takeIf { item ->
            activeGenerationId == generationId &&
                item.generationId == generationId &&
                item.ownerSessionId == sessionId
        }
    }

    suspend fun destinationFor(
        generationId: ServerGenerationId,
        sessionId: BrowserSessionId,
        transferId: FileTransferId,
    ): FileDestinationId? = mutex.withLock {
        val item = state.value.item(transferId)
        if (
            item != null &&
            activeGenerationId == generationId &&
            item.generationId == generationId &&
            item.ownerSessionId == sessionId
        ) {
            destinations[transferId]
        } else {
            null
        }
    }

    suspend fun issueDownloadGrant(
        generationId: ServerGenerationId,
        sessionId: BrowserSessionId,
        transferId: FileTransferId,
    ): IssuedDownloadGrant? = mutex.withLock {
        val item = state.value.item(transferId) ?: return@withLock null
        if (
            activeGenerationId != generationId ||
            item.generationId != generationId ||
            item.ownerSessionId != sessionId ||
            item.metadata.direction != FileTransferDirection.ANDROID_TO_BROWSER ||
            item.phase != FileTransferPhase.CONNECTING
        ) {
            return@withLock null
        }
        downloadGrantRegistry.issue(generationId, sessionId, transferId)
    }

    suspend fun consumeDownloadGrant(
        token: String,
        generationId: ServerGenerationId,
        transferId: FileTransferId,
    ): DownloadGrantScope? = mutex.withLock {
        if (activeGenerationId != generationId) return@withLock null
        val scope = downloadGrantRegistry.consume(token, generationId, transferId)
            ?: return@withLock null
        val item = state.value.item(transferId)
        if (
            item == null ||
            item.ownerSessionId != scope.sessionId ||
            item.metadata.direction != FileTransferDirection.ANDROID_TO_BROWSER ||
            item.phase != FileTransferPhase.CONNECTING
        ) {
            return@withLock null
        }
        scope
    }

    suspend fun onSessionDisconnected(
        generationId: ServerGenerationId,
        sessionId: BrowserSessionId,
    ) {
        cancelOwnedTransfers(generationId, sessionId)
    }

    suspend fun onSessionRevoked(
        generationId: ServerGenerationId,
        sessionId: BrowserSessionId,
    ) {
        cancelOwnedTransfers(generationId, sessionId)
    }

    suspend fun onNetworkFailure(transferId: FileTransferId) {
        mutex.withLock {
            val item = state.value.item(transferId) ?: return@withLock
            if (!isCurrent(item) || item.phase.isTerminal) return@withLock
            downloadGrantRegistry.invalidateTransfer(item.generationId, transferId)
            cleanupResources(transferId)
            transitionWithWifiLock(
                transferId,
                FileTransferEvent.Failed(FileTransferFailure.StreamFailed),
            )
            destinations.remove(transferId)
        }
    }

    suspend fun transition(
        transferId: FileTransferId,
        event: FileTransferEvent,
    ): FileTransferState? = mutex.withLock {
        val item = state.value.item(transferId) ?: return@withLock null
        if (!isCurrent(item)) return@withLock null
        transitionWithWifiLock(transferId, event)
    }

    private suspend fun cancelOwnedTransfers(
        generationId: ServerGenerationId,
        sessionId: BrowserSessionId,
    ) {
        mutex.withLock {
            if (activeGenerationId != generationId) return@withLock
            downloadGrantRegistry.invalidateSession(generationId, sessionId)
            state.value.items
                .filter { item ->
                    item.generationId == generationId &&
                        item.ownerSessionId == sessionId &&
                        !item.phase.isTerminal
                }
                .forEach { item ->
                    cleanupResources(item.metadata.id)
                    transitionWithWifiLock(item.metadata.id, FileTransferEvent.Cancelled)
                    destinations.remove(item.metadata.id)
                }
        }
    }

    private suspend fun cleanupAllResources() {
        resources.keys.toList().forEach { transferId -> cleanupResources(transferId) }
    }

    private suspend fun cleanupResources(transferId: FileTransferId) {
        val transferResources = resources.remove(transferId) ?: return
        withContext(NonCancellable) {
            runCatching { transferResources.cancelJob() }
            runCatching { transferResources.closeStreams() }
            runCatching { transferResources.cleanupPartial() }
        }
    }

    private suspend fun transitionWithWifiLock(
        transferId: FileTransferId,
        event: FileTransferEvent,
    ): FileTransferState? {
        val previous = state.value.item(transferId)
        val updated = scheduler.transition(transferId, event)
        if (previous?.phase != FileTransferPhase.TRANSFERRING &&
            updated?.phase == FileTransferPhase.TRANSFERRING
        ) {
            wifiLock.acquire(transferId)
        } else if (
            previous?.phase == FileTransferPhase.TRANSFERRING &&
            updated?.phase != FileTransferPhase.TRANSFERRING
        ) {
            wifiLock.release(transferId)
        }
        if (
            previous?.phase?.isTerminal != true &&
            updated?.phase?.isTerminal == true
        ) {
            runCatching {
                historyRecorder.recordTerminal(updated)
            }
        }
        return updated
    }

    suspend fun snapshotFor(
        generationId: ServerGenerationId,
        sessionId: BrowserSessionId,
    ): FileTransferSnapshot = mutex.withLock {
        if (activeGenerationId != generationId) {
            return@withLock FileTransferSnapshot(emptyList())
        }
        FileTransferSnapshot(
            state.value.items
                .asSequence()
                .filter { item ->
                    item.generationId == generationId &&
                        item.ownerSessionId == sessionId
                }
                .distinctBy { item -> item.metadata.id }
                .toList(),
        )
    }

    private fun isCurrent(item: FileTransferState): Boolean =
        item.generationId == activeGenerationId

    private fun CreateFileTransfersRequest.fingerprint(): String {
        val canonical = batchId + "\u0002" + files.joinToString(separator = "\u0000") { file ->
            listOf(
                file.id.value,
                file.displayName,
                file.sizeBytes.toString(),
                file.mimeType,
                file.sha256.lowercase(),
                file.direction.name,
            ).joinToString(separator = "\u0001")
        }
        return MessageDigest.getInstance("SHA-256")
            .digest(canonical.encodeToByteArray())
            .joinToString(separator = "") { byte -> "%02x".format(byte) }
    }
}
