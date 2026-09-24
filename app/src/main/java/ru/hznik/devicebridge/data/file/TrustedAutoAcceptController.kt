package ru.hznik.devicebridge.data.file

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ru.hznik.devicebridge.domain.file.AutoAcceptStatusSource
import ru.hznik.devicebridge.domain.file.FileTransferDirection
import ru.hznik.devicebridge.domain.file.FileTransferId
import ru.hznik.devicebridge.domain.file.FileTransferOperationResult
import ru.hznik.devicebridge.domain.file.FileTransferPhase
import ru.hznik.devicebridge.domain.file.FileTransferSnapshot
import ru.hznik.devicebridge.domain.file.FileTransferState
import ru.hznik.devicebridge.domain.repository.FileTransferRepository
import ru.hznik.devicebridge.domain.session.BrowserSessionState
import ru.hznik.devicebridge.domain.settings.DeviceSettings

/** Starts and stops automatic acceptance together with a server session generation. */
interface AutoAcceptLifecycle {
    fun activate()

    fun deactivate()

    object None : AutoAcceptLifecycle {
        override fun activate() = Unit

        override fun deactivate() = Unit
    }
}

fun interface PersistedDestinationOpener {
    fun open(uri: String): DestinationApproval
}

/**
 * Accepts Browser → Android offers of trusted sessions into the default destination without UI,
 * using the same coordinator approval as the manual path. Offers of ordinary sessions, a disabled
 * setting or an unusable folder stay in CONNECTING for manual approval.
 *
 * A retried upload that kept a part in the default destination is approved there as well, so
 * «Продолжить» in the browser does not need another tap on the phone. So are files the person
 * accepted with «Принять» in a notification.
 */
class TrustedAutoAcceptController(
    private val transfers: FileTransferRepository,
    private val sessions: StateFlow<BrowserSessionState>,
    private val settings: Flow<DeviceSettings>,
    private val destinations: PersistedDestinationOpener,
    private val leases: FileDestinationLeaseRegistry,
    private val isResumeRetry: (FileTransferId) -> Boolean = { false },
    private val hasRetainedPart: suspend (FileTransferState, treeUri: String) -> Boolean =
        { _, _ -> false },
    private val notificationAccepted: NotificationAcceptedTransfers = NotificationAcceptedTransfers(),
) : AutoAcceptStatusSource {
    private val mutableAutoAccepted = MutableStateFlow<Set<FileTransferId>>(emptySet())
    override val autoAccepted: StateFlow<Set<FileTransferId>> = mutableAutoAccepted

    private val mutablePaused = MutableStateFlow<Set<FileTransferId>>(emptySet())
    override val paused: StateFlow<Set<FileTransferId>> = mutablePaused

    private val mutex = Mutex()
    private val processed: MutableSet<FileTransferId> = ConcurrentHashMap.newKeySet()
    private var job: Job? = null

    @Synchronized
    fun start(scope: CoroutineScope) {
        stop()
        job = scope.launch {
            combine(
                transfers.state,
                sessions,
                settings,
                notificationAccepted.ids,
            ) { snapshot, sessionState, current, accepted ->
                Evaluation(snapshot, sessionState, current, accepted)
            }.collect { (snapshot, sessionState, current, accepted) ->
                mutex.withLock { evaluate(snapshot, sessionState, current, accepted) }
            }
        }
    }

    @Synchronized
    fun stop() {
        job?.cancel()
        job = null
        processed.clear()
        notificationAccepted.clear()
        mutableAutoAccepted.value = emptySet()
        mutablePaused.value = emptySet()
    }

    private suspend fun evaluate(
        snapshot: FileTransferSnapshot,
        sessionState: BrowserSessionState,
        current: DeviceSettings,
        acceptedFromNotification: Set<FileTransferId>,
    ) {
        val paused = mutableSetOf<FileTransferId>()
        // A retry brings the same transfer back to CONNECTING; it may be decided again.
        // Its registration is stale then even if the upload never opened an output.
        processed.removeAll { id ->
            val finished = snapshot.item(id)?.phase?.isTerminal != false
            if (finished) leases.release(id)
            finished
        }
        // A decision made elsewhere, or a file that went away, ends a notification «Принять».
        acceptedFromNotification.filter { id ->
            val phase = snapshot.item(id)?.phase
            phase != FileTransferPhase.QUEUED && phase != FileTransferPhase.CONNECTING
        }.forEach(notificationAccepted::consume)
        val awaiting = snapshot.items.filter { item ->
            item.metadata.direction == FileTransferDirection.BROWSER_TO_ANDROID &&
                item.phase == FileTransferPhase.CONNECTING
        }
        for (item in awaiting) {
            val id = item.metadata.id
            if (id in processed) continue
            // Trust is read at acceptance time: a revoked browser no longer owns a live session.
            val owner = sessionState.sessions.firstOrNull { session ->
                session.id == item.ownerSessionId && session.generationId == item.generationId
            }
            if (owner == null) continue
            val trusted = owner.trustedBrowserId != null && current.autoAcceptTrustedFiles
            val resume = isResumeRetry(id)
            val fromNotification = id in acceptedFromNotification
            if (!trusted && !resume && !fromNotification) continue
            val tree = current.destinationTree
            if (tree == null) {
                // The folder was cleared after «Принять»: the person has to choose one now.
                if (fromNotification) paused += id
                continue
            }
            // An ordinary session continues only into the folder that holds its kept part.
            if (!trusted && !fromNotification && !hasRetainedPart(item, tree.value)) continue
            val lease = when (val approval = destinations.open(tree.value)) {
                is DestinationApproval.Approved -> approval.lease
                DestinationApproval.Cancelled,
                DestinationApproval.Unavailable -> {
                    paused += id
                    continue
                }
            }
            processed += id
            val destinationId = leases.registerIfAbsent(id, lease)
            if (destinationId == null) {
                // A manual decision already owns this transfer.
                lease.release()
                continue
            }
            if (transfers.approve(id, destinationId) == FileTransferOperationResult.Accepted) {
                if (trusted) mutableAutoAccepted.update { it + id }
            } else {
                leases.release(id, lease)
            }
        }
        mutablePaused.value = paused
    }

    private data class Evaluation(
        val snapshot: FileTransferSnapshot,
        val sessions: BrowserSessionState,
        val settings: DeviceSettings,
        val acceptedFromNotification: Set<FileTransferId>,
    )
}
