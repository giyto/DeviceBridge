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
import ru.hznik.devicebridge.domain.file.FileTransferReducer
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
    private val retrySourceValidator: FileRetrySourceValidator =
        FileRetrySourceValidator.alwaysValid(),
    private val historyRecorder: FileTerminalHistoryRecorder =
        FileTerminalHistoryRecorder { },
    private val nowEpochMillis: () -> Long = System::currentTimeMillis,
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
    /** When each Android -> Browser download was cut off; it may resume for a while. */
    private val interruptedDownloads = LinkedHashMap<FileTransferId, Long>()
    private val resumeRetries: MutableSet<FileTransferId> =
        java.util.concurrent.ConcurrentHashMap.newKeySet()
    private var activeGenerationId: ServerGenerationId? = null

    init {
        require(maxItems > 0)
    }

    override val state: StateFlow<FileTransferSnapshot> = scheduler.state

    suspend fun activate(generationId: ServerGenerationId) {
        mutex.withLock {
            cleanupAllResources(retain = true)
            resumeRetries.clear()
            interruptedDownloads.clear()
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
                    // A server stop keeps large partial uploads for a later resume.
                    cleanupResources(item.metadata.id, retain = true)
                    transitionWithWifiLock(item.metadata.id, FileTransferEvent.Cancelled)
                }
            resumeRetries.clear()
            interruptedDownloads.clear()
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
            resumeRetries.remove(transferId)
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
        if (item.metadata.direction == FileTransferDirection.ANDROID_TO_BROWSER) {
            when (retrySourceValidator.validate(item)) {
                FileRetrySourceValidation.VALID -> Unit
                FileRetrySourceValidation.UNAVAILABLE,
                FileRetrySourceValidation.CHANGED,
                -> return@withLock FileTransferOperationResult.Rejected(
                    FileTransferFailure.SourceUnavailable,
                )
            }
        }
        downloadGrantRegistry.invalidateTransfer(item.generationId, transferId)
        cleanupResources(transferId)
        destinations.remove(transferId)
        if (item.hasResumablePart()) {
            resumeRetries += transferId
        } else {
            resumeRetries -= transferId
        }
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
        cancelOwnedTransfers(generationId, sessionId, revoked = false)
    }

    suspend fun onSessionRevoked(
        generationId: ServerGenerationId,
        sessionId: BrowserSessionId,
    ) {
        cancelOwnedTransfers(generationId, sessionId, revoked = true)
    }

    suspend fun onNetworkFailure(transferId: FileTransferId) {
        mutex.withLock {
            val item = state.value.item(transferId) ?: return@withLock
            if (!isCurrent(item) || item.phase.isTerminal) return@withLock
            val download = item.metadata.direction == FileTransferDirection.ANDROID_TO_BROWSER
            downloadGrantRegistry.invalidateTransfer(
                item.generationId,
                transferId,
                keepResumable = download,
            )
            cleanupResources(transferId, retain = true)
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
        revoked: Boolean,
    ) {
        mutex.withLock {
            if (activeGenerationId != generationId) return@withLock
            val owned = state.value.items.filter { item ->
                item.generationId == generationId &&
                    item.ownerSessionId == sessionId &&
                    !item.phase.isTerminal
            }
            // A lost network drops the download together with its session; the browser may still
            // continue that download. A revoked browser may not.
            val interruptedDownloads = if (revoked) {
                emptySet()
            } else {
                owned
                    .filter { item ->
                        item.metadata.direction == FileTransferDirection.ANDROID_TO_BROWSER &&
                            item.phase == FileTransferPhase.TRANSFERRING
                    }
                    .map { item -> item.metadata.id }
                    .toSet()
            }
            downloadGrantRegistry.invalidateSession(
                generationId,
                sessionId,
                keepResumable = interruptedDownloads,
            )
            owned.forEach { item ->
                cleanupResources(item.metadata.id, retain = true)
                transitionWithWifiLock(
                    item.metadata.id,
                    FileTransferEvent.Failed(
                        if (item.metadata.id in interruptedDownloads) {
                            FileTransferFailure.StreamFailed
                        } else {
                            FileTransferFailure.SessionUnavailable
                        },
                    ),
                )
                destinations.remove(item.metadata.id)
            }
        }
    }

    private suspend fun cleanupAllResources(retain: Boolean) {
        resources.keys.toList().forEach { transferId ->
            cleanupResources(transferId, retain = retain)
        }
    }

    private suspend fun cleanupResources(
        transferId: FileTransferId,
        successful: Boolean = false,
        retain: Boolean = false,
    ) {
        val transferResources = resources.remove(transferId) ?: return
        withContext(NonCancellable) {
            if (!successful) {
                runCatching { transferResources.cancelJob() }
            }
            runCatching { transferResources.closeStreams() }
            if (!successful) {
                runCatching { transferResources.cleanupPartial(retain) }
            }
        }
    }

    private suspend fun transitionWithWifiLock(
        transferId: FileTransferId,
        event: FileTransferEvent,
    ): FileTransferState? {
        val previous = state.value.item(transferId) ?: return null
        val preview = FileTransferReducer.reduce(previous, event)
        val enteringTerminal = !previous.phase.isTerminal && preview.phase.isTerminal
        if (enteringTerminal) {
            val interruptedDownload =
                previous.metadata.direction == FileTransferDirection.ANDROID_TO_BROWSER &&
                    preview.failure == FileTransferFailure.StreamFailed
            downloadGrantRegistry.invalidateTransfer(
                previous.generationId,
                transferId,
                keepResumable = interruptedDownload,
            )
            if (interruptedDownload) {
                interruptedDownloads[transferId] = nowEpochMillis()
            } else {
                interruptedDownloads.remove(transferId)
            }
            cleanupResources(
                transferId,
                successful = preview.phase == FileTransferPhase.COMPLETED,
                retain = preview.failure.keepsPartialUpload(),
            )
            destinations.remove(transferId)
            if (preview.phase != FileTransferPhase.FAILED) resumeRetries.remove(transferId)
        }

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
        if (enteringTerminal && updated != null) {
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

    /**
     * Lets the browser continue an interrupted Android -> Browser download with the grant it
     * already used, within [DOWNLOAD_RESUME_WINDOW_MILLIS] and the same server generation.
     * [expectedSha256] is the validator the browser holds; a different one or a changed source
     * gives [DownloadResume.Stale].
     */
    suspend fun resumeDownload(
        token: String,
        generationId: ServerGenerationId,
        transferId: FileTransferId,
        offsetBytes: Long,
        expectedSha256: String? = null,
    ): DownloadResume {
        val candidate = state.value.item(transferId) ?: return DownloadResume.Rejected
        if (downloadGrantRegistry.resumeScope(token, generationId, transferId) == null) {
            return DownloadResume.Rejected
        }
        if (expectedSha256 != null && !expectedSha256.equals(candidate.metadata.sha256, ignoreCase = true)) {
            return DownloadResume.Stale
        }
        // Re-reading the source is slow, so it happens before taking the lock.
        if (retrySourceValidator.validate(candidate) != FileRetrySourceValidation.VALID) {
            return DownloadResume.Stale
        }
        return mutex.withLock {
            val item = state.value.item(transferId)
            val interruptedAt = interruptedDownloads[transferId]
            when {
                activeGenerationId != generationId || item == null || interruptedAt == null ->
                    DownloadResume.Rejected
                item.metadata.direction != FileTransferDirection.ANDROID_TO_BROWSER ||
                    item.phase != FileTransferPhase.FAILED ||
                    item.failure != FileTransferFailure.StreamFailed ->
                    DownloadResume.Rejected
                nowEpochMillis() - interruptedAt > DOWNLOAD_RESUME_WINDOW_MILLIS -> {
                    interruptedDownloads.remove(transferId)
                    downloadGrantRegistry.invalidateTransfer(generationId, transferId)
                    DownloadResume.Rejected
                }
                else -> {
                    val scope = downloadGrantRegistry.resumeScope(token, generationId, transferId)
                    if (scope == null || scope.sessionId != item.ownerSessionId) {
                        DownloadResume.Rejected
                    } else {
                        val resumed = scheduler.resume(transferId, offsetBytes)
                        if (resumed == null) {
                            DownloadResume.Busy
                        } else {
                            interruptedDownloads.remove(transferId)
                            wifiLock.acquire(transferId)
                            DownloadResume.Allowed(scope)
                        }
                    }
                }
            }
        }
    }

    /**
     * True for a retried upload that has a part kept from its interrupted attempt: it may be
     * approved again into the folder that holds that part without asking the user.
     */
    fun isResumeRetry(transferId: FileTransferId): Boolean = transferId in resumeRetries

    private fun FileTransferState.hasResumablePart(): Boolean =
        metadata.direction == FileTransferDirection.BROWSER_TO_ANDROID && resumableBytes() != null

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

/** How long an interrupted download may be continued by the browser. */
const val DOWNLOAD_RESUME_WINDOW_MILLIS = 15L * 60 * 1000

sealed interface DownloadResume {
    data class Allowed(val scope: DownloadGrantScope) : DownloadResume

    /** Another download of the same direction is active; the browser may try again later. */
    data object Busy : DownloadResume

    /** The browser's copy no longer matches the source. */
    data object Stale : DownloadResume

    data object Rejected : DownloadResume
}
