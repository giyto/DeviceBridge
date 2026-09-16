package ru.hznik.devicebridge.data.session

import ru.hznik.devicebridge.data.server.MonotonicClock
import ru.hznik.devicebridge.data.session.security.SessionSecretGenerator
import ru.hznik.devicebridge.domain.session.PairingCodeState

const val PAIRING_CODE_TTL_MS: Long = 5 * 60 * 1_000L
const val PENDING_REQUEST_TTL_MS: Long = 60 * 1_000L

class SessionLifetimePolicy(
    private val clock: MonotonicClock,
    private val secretGenerator: SessionSecretGenerator,
) {
    fun newPairingCode(): PairingCodeState = PairingCodeState(
        value = secretGenerator.newPairingCode(),
        expiresAtElapsedRealtimeMs = expiryAfter(PAIRING_CODE_TTL_MS),
    )

    fun currentOrRotated(current: PairingCodeState): PairingCodeState =
        if (isPairingCodeActive(current)) current else newPairingCode()

    fun isPairingCodeActive(code: PairingCodeState): Boolean = !isExpired(
        code.expiresAtElapsedRealtimeMs,
    )

    fun newPendingExpiry(): Long = expiryAfter(PENDING_REQUEST_TTL_MS)

    fun isExpired(expiresAtElapsedRealtimeMs: Long): Boolean =
        clock.nowMs() >= expiresAtElapsedRealtimeMs

    private fun expiryAfter(durationMs: Long): Long {
        val now = clock.nowMs()
        require(now >= 0) { "Monotonic clock must not be negative" }
        return Math.addExact(now, durationMs)
    }
}
