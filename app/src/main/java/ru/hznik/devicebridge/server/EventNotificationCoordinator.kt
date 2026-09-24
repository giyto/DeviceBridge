package ru.hznik.devicebridge.server

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import ru.hznik.devicebridge.data.server.MonotonicClock
import ru.hznik.devicebridge.domain.file.FileTransferDirection
import ru.hznik.devicebridge.domain.file.FileTransferId
import ru.hznik.devicebridge.domain.file.FileTransferSnapshot
import ru.hznik.devicebridge.domain.session.BrowserSessionState
import ru.hznik.devicebridge.domain.settings.DeviceSettings
import ru.hznik.devicebridge.domain.text.TextTransferState

/**
 * Shows phone notifications for events while no DeviceBridge screen is visible. It only watches
 * existing state; deciding, accepting and copying stay with the coordinators that own them.
 */
class EventNotificationCoordinator(
    private val sessions: StateFlow<BrowserSessionState>,
    private val transfers: StateFlow<FileTransferSnapshot>,
    private val texts: StateFlow<TextTransferState>,
    private val autoAccepted: StateFlow<Set<FileTransferId>>,
    private val paused: StateFlow<Set<FileTransferId>>,
    private val acceptedFromNotification: StateFlow<Set<FileTransferId>>,
    private val settings: Flow<DeviceSettings>,
    private val appVisible: StateFlow<Boolean>,
    private val isResumeRetry: (FileTransferId) -> Boolean,
    private val clock: MonotonicClock,
    private val publisher: EventNotificationPublisher,
) {
    private val planner = EventNotificationPlanner()
    private var job: Job? = null

    @Synchronized
    fun start(scope: CoroutineScope) {
        if (job != null) return
        job = scope.launch {
            val handled = combine(autoAccepted, paused, acceptedFromNotification, settings) {
                    auto, pausedIds, fromNotification, current ->
                Handling(auto + fromNotification, pausedIds, current)
            }
            combine(sessions, transfers, texts, handled, appVisible) {
                    sessionState, snapshot, textState, handling, visible ->
                snapshotOf(sessionState, snapshot, textState, handling) to visible
            }.collect { (snapshot, visible) ->
                planner.plan(snapshot, visible).forEach { command ->
                    when (command) {
                        is EventNotificationCommand.Show -> publisher.show(command.notice, command.alert)
                        is EventNotificationCommand.Cancel -> publisher.cancel(command.key)
                    }
                }
            }
        }
    }

    private fun snapshotOf(
        sessionState: BrowserSessionState,
        snapshot: FileTransferSnapshot,
        textState: TextTransferState,
        handling: Handling,
    ): EventSnapshot {
        val autoAcceptFolder = handling.settings.autoAcceptTrustedFiles &&
            handling.settings.destinationTree != null
        val trustedSessions = sessionState.sessions
            .filter { autoAcceptFolder && it.trustedBrowserId != null }
            .mapTo(mutableSetOf()) { it.id }
        // Accepted by the app without asking: the notification would only flash.
        val handled = handling.accepted + snapshot.items
            .filter { item ->
                item.metadata.direction == FileTransferDirection.BROWSER_TO_ANDROID &&
                    (item.ownerSessionId in trustedSessions || isResumeRetry(item.metadata.id))
            }
            .map { it.metadata.id }
        return EventSnapshot(
            generationId = sessionState.generationId,
            nowElapsedMs = clock.nowMs(),
            pendingRequests = sessionState.pendingRequests,
            transfers = snapshot.items,
            texts = textState.items,
            handledTransfers = handled,
            pausedTransfers = handling.paused,
            folderChosen = handling.settings.destinationTree != null,
        )
    }

    private data class Handling(
        val accepted: Set<FileTransferId>,
        val paused: Set<FileTransferId>,
        val settings: DeviceSettings,
    )
}
