package ru.hznik.devicebridge.data.server

import ru.hznik.devicebridge.domain.model.ServerLifecycleState

class ServerUptimeCalculator(
    private val monotonicClock: MonotonicClock,
) {
    fun uptimeMs(state: ServerLifecycleState): Long? {
        val running = state as? ServerLifecycleState.Running ?: return null
        return (monotonicClock.nowMs() - running.startedAtElapsedRealtimeMs)
            .coerceAtLeast(0)
    }
}
