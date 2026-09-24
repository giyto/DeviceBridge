package ru.hznik.devicebridge.server

import ru.hznik.devicebridge.domain.file.FileTransferDirection
import ru.hznik.devicebridge.domain.file.FileTransferId
import ru.hznik.devicebridge.domain.file.FileTransferPhase
import ru.hznik.devicebridge.domain.file.FileTransferState
import ru.hznik.devicebridge.domain.session.BrowserSessionId
import ru.hznik.devicebridge.domain.session.PairingRequestId
import ru.hznik.devicebridge.domain.session.PendingBrowserRequest
import ru.hznik.devicebridge.domain.session.ServerGenerationId
import ru.hznik.devicebridge.domain.text.TextContentKind
import ru.hznik.devicebridge.domain.text.TextMessageId
import ru.hznik.devicebridge.domain.text.TextTransferDirection
import ru.hznik.devicebridge.domain.text.TextTransferItem

/** Everything event notifications are derived from, taken at one moment. */
data class EventSnapshot(
    val generationId: ServerGenerationId?,
    val nowElapsedMs: Long,
    val pendingRequests: List<PendingBrowserRequest> = emptyList(),
    val transfers: List<FileTransferState> = emptyList(),
    val texts: List<TextTransferItem> = emptyList(),
    /** Incoming transfers the app accepts by itself: a trusted browser, a resume, a notification "Принять". */
    val handledTransfers: Set<FileTransferId> = emptySet(),
    /** Transfers the app meant to accept but could not open the folder for; the person decides. */
    val pausedTransfers: Set<FileTransferId> = emptySet(),
    val folderChosen: Boolean = false,
)

sealed interface EventNotificationCommand {
    data class Show(val notice: EventNotice, val alert: Boolean) : EventNotificationCommand
    data class Cancel(val key: EventNotificationKey) : EventNotificationCommand
}

/**
 * Turns successive snapshots into notification commands by comparing each with the previous one.
 * Nothing new is shown while the app is on screen, but what is shown still follows the events:
 * a decided request or offer disappears wherever it was decided.
 */
class EventNotificationPlanner {
    private var initialized = false
    private var generationId: ServerGenerationId? = null
    private val shown = mutableMapOf<EventNotificationKey, EventNotice>()
    private val seenRequests = mutableSetOf<PairingRequestId>()
    private val seenTexts = mutableSetOf<Pair<BrowserSessionId, TextMessageId>>()
    private val seenAwaiting = mutableSetOf<FileTransferId>()

    /** Transfers seen unfinished since the last result of their browser and direction. */
    private val waves = mutableMapOf<EventNotificationKey.TransferResult, MutableSet<FileTransferId>>()

    /** Transfers seen moving bytes; an offer that ends without ever starting is not a failure. */
    private val started = mutableSetOf<FileTransferId>()

    fun plan(snapshot: EventSnapshot, appVisible: Boolean): List<EventNotificationCommand> {
        val commands = mutableListOf<EventNotificationCommand>()
        // The first snapshot is what already happened; only later changes are events.
        val quiet = appVisible || !initialized
        initialized = true
        if (snapshot.generationId != generationId) {
            closeGeneration(commands)
            generationId = snapshot.generationId
        }
        if (snapshot.generationId == null) return commands

        planRequests(snapshot, quiet, commands)
        planTexts(snapshot, quiet, commands)
        planIncomingFiles(snapshot, quiet, appVisible, commands)
        planResults(snapshot, quiet, commands)
        return commands
    }

    /** Text lives only in memory and requests and offers die with the server; results stay. */
    private fun closeGeneration(commands: MutableList<EventNotificationCommand>) {
        shown.keys.filterNot { it is EventNotificationKey.TransferResult }.forEach { commands.cancel(it) }
        seenRequests.clear()
        seenTexts.clear()
        seenAwaiting.clear()
        waves.clear()
        started.clear()
    }

    private fun planRequests(
        snapshot: EventSnapshot,
        quiet: Boolean,
        commands: MutableList<EventNotificationCommand>,
    ) {
        val current = snapshot.pendingRequests.associateBy { it.id }
        shown.keys.filterIsInstance<EventNotificationKey.Pairing>()
            .filter { it.requestId !in current }
            .forEach { commands.cancel(it) }
        seenRequests.retainAll(current.keys)
        for (request in snapshot.pendingRequests) {
            if (!seenRequests.add(request.id) || quiet) continue
            commands.show(
                EventNotice.PairingRequest(
                    requestId = request.id,
                    browserLabel = request.browserLabel,
                    remainingMs = request.expiresAtElapsedRealtimeMs - snapshot.nowElapsedMs,
                ),
                alert = true,
            )
        }
    }

    private fun planTexts(
        snapshot: EventSnapshot,
        quiet: Boolean,
        commands: MutableList<EventNotificationCommand>,
    ) {
        val incoming = snapshot.texts.filter { it.direction == TextTransferDirection.BROWSER_TO_ANDROID }
        val current = incoming.mapTo(mutableSetOf()) { it.sessionId to it.id }
        shown.keys.filterIsInstance<EventNotificationKey.Text>()
            .filter { (it.sessionId to it.messageId) !in current }
            .forEach { commands.cancel(it) }
        seenTexts.retainAll(current)
        for (item in incoming) {
            if (!seenTexts.add(item.sessionId to item.id) || quiet) continue
            commands.show(
                EventNotice.IncomingText(
                    sessionId = item.sessionId,
                    messageId = item.id,
                    content = item.content,
                    isLink = item.contentKind == TextContentKind.LINK,
                ),
                alert = true,
            )
        }
    }

    private fun planIncomingFiles(
        snapshot: EventSnapshot,
        quiet: Boolean,
        appVisible: Boolean,
        commands: MutableList<EventNotificationCommand>,
    ) {
        val awaiting = snapshot.transfers.filter { item ->
            val id = item.metadata.id
            item.metadata.direction == FileTransferDirection.BROWSER_TO_ANDROID &&
                (item.phase == FileTransferPhase.QUEUED || item.phase == FileTransferPhase.CONNECTING) &&
                (id !in snapshot.handledTransfers || id in snapshot.pausedTransfers)
        }
        val groups = awaiting.groupBy { it.ownerSessionId }
        shown.keys.filterIsInstance<EventNotificationKey.IncomingFiles>()
            .filter { it.sessionId !in groups }
            .forEach { commands.cancel(it) }
        val awaitingIds = awaiting.mapTo(mutableSetOf()) { it.metadata.id }
        seenAwaiting.retainAll(awaitingIds)
        for ((sessionId, items) in groups) {
            val ids = items.map { it.metadata.id }
            val added = ids.filter(seenAwaiting::add)
            val notice = EventNotice.IncomingFiles(
                sessionId = sessionId,
                transferIds = ids,
                firstName = items.first().metadata.displayName,
                totalBytes = items.sumOf { it.metadata.sizeBytes },
                acceptable = snapshot.folderChosen && ids.none { it in snapshot.pausedTransfers },
            )
            val previous = shown[notice.key]
            when {
                previous == null && added.isNotEmpty() && !quiet -> commands.show(notice, alert = true)
                previous != null && previous != notice ->
                    commands.show(notice, alert = added.isNotEmpty() && !appVisible)
            }
        }
    }

    private fun planResults(
        snapshot: EventSnapshot,
        quiet: Boolean,
        commands: MutableList<EventNotificationCommand>,
    ) {
        val byGroup = snapshot.transfers.groupBy { item ->
            EventNotificationKey.TransferResult(item.ownerSessionId, item.metadata.direction)
        }
        for ((key, items) in byGroup) {
            items.filterNot { it.phase.isTerminal }
                .forEach { waves.getOrPut(key) { mutableSetOf() } += it.metadata.id }
        }
        snapshot.transfers
            .filter { it.phase == FileTransferPhase.TRANSFERRING || it.phase == FileTransferPhase.VERIFYING }
            .forEach { started += it.metadata.id }
        for ((key, wave) in waves.entries.toList()) {
            val items = byGroup[key].orEmpty()
            if (items.any { !it.phase.isTerminal }) continue
            waves.remove(key)
            val finished = items.filter { it.metadata.id in wave }
            val completed = finished.count { it.phase == FileTransferPhase.COMPLETED }
            // An offer nobody accepted, left behind by its browser, did not fail as a transfer.
            val failed = finished.count { it.phase == FileTransferPhase.FAILED && it.metadata.id in started }
            val cancelled = finished.count {
                it.phase == FileTransferPhase.CANCELLED && it.metadata.id in started
            }
            started -= wave
            // Declined or cancelled on purpose: nothing to report.
            if (completed + failed == 0 || quiet) continue
            commands.show(
                EventNotice.TransferResult(key.sessionId, key.direction, completed, failed, cancelled),
                alert = true,
            )
        }
    }

    private fun MutableList<EventNotificationCommand>.show(notice: EventNotice, alert: Boolean) {
        shown[notice.key] = notice
        add(EventNotificationCommand.Show(notice, alert))
    }

    private fun MutableList<EventNotificationCommand>.cancel(key: EventNotificationKey) {
        shown.remove(key)
        add(EventNotificationCommand.Cancel(key))
    }
}
