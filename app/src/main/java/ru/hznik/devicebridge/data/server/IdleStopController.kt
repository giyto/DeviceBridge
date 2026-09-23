package ru.hznik.devicebridge.data.server

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import ru.hznik.devicebridge.domain.file.FileTransferSnapshot
import ru.hznik.devicebridge.domain.model.ServerLifecycleState
import ru.hznik.devicebridge.domain.session.BrowserSessionState
import ru.hznik.devicebridge.domain.settings.IdleStopTimeout
import ru.hznik.devicebridge.domain.text.TextTransferState
import ru.hznik.devicebridge.domain.text.TextTransferStatus

/**
 * Stops a running server after [IdleStopTimeout] without live browser connections, pending
 * pairing or unfinished operations. The deadline uses [MonotonicClock] (elapsed realtime, which
 * keeps counting while the device sleeps) and is re-checked at least every [tickMs], so a
 * deadline that passed during sleep fires right after the process wakes up.
 */
class IdleStopController(
    private val lifecycle: Flow<ServerLifecycleState>,
    private val activeConnections: Flow<Int>,
    private val sessions: Flow<BrowserSessionState>,
    private val textTransfers: Flow<TextTransferState>,
    private val fileTransfers: Flow<FileTransferSnapshot>,
    private val timeout: Flow<IdleStopTimeout>,
    private val clock: MonotonicClock,
    private val wallClockMs: () -> Long,
    private val tickMs: Long = DEFAULT_TICK_MS,
) {
    private val mutableStopAtWallClockMs = MutableStateFlow<Long?>(null)

    /** Wall-clock time the server will stop at, or null while it is not counting down. */
    val stopAtWallClockMs: StateFlow<Long?> = mutableStopAtWallClockMs.asStateFlow()

    fun run(scope: CoroutineScope, onIdle: suspend (IdleStopTimeout) -> Unit): Job = scope.launch {
        val activity = combine(activeConnections, sessions, textTransfers, fileTransfers) {
                connections, sessionState, texts, files ->
            connections > 0 ||
                sessionState.pendingRequests.isNotEmpty() ||
                texts.items.any {
                    it.status == TextTransferStatus.PENDING || it.status == TextTransferStatus.SENDING
                } ||
                files.items.any { !it.phase.isTerminal }
        }
        combine(lifecycle, activity, timeout) { state, active, selected ->
            IdleInputs(
                runningGeneration = (state as? ServerLifecycleState.Running)?.generation,
                active = active,
                timeout = selected,
            )
        }
            .distinctUntilChanged()
            .collectLatest { inputs ->
                val minutes = inputs.timeout.minutes
                if (inputs.runningGeneration == null || inputs.active || minutes == null) {
                    mutableStopAtWallClockMs.value = null
                    return@collectLatest
                }
                val intervalMs = minutes * MS_PER_MINUTE
                val deadline = clock.nowMs() + intervalMs
                mutableStopAtWallClockMs.value = wallClockMs() + intervalMs
                try {
                    while (true) {
                        val remaining = deadline - clock.nowMs()
                        if (remaining <= 0) break
                        delay(minOf(remaining, tickMs))
                    }
                } finally {
                    mutableStopAtWallClockMs.value = null
                }
                onIdle(inputs.timeout)
            }
    }

    private data class IdleInputs(
        val runningGeneration: Long?,
        val active: Boolean,
        val timeout: IdleStopTimeout,
    )

    private companion object {
        const val DEFAULT_TICK_MS = 30_000L
        const val MS_PER_MINUTE = 60_000L
    }
}
