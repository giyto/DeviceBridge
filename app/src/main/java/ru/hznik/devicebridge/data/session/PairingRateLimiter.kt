package ru.hznik.devicebridge.data.session

import ru.hznik.devicebridge.data.server.MonotonicClock

const val MAX_PAIRING_FAILURES = 5
const val PAIRING_BLOCK_DURATION_MS = 60_000L
const val DEFAULT_MAX_RATE_LIMIT_RECORDS = 256

sealed interface RateLimitDecision {
    data class Allowed(val remainingAttempts: Int) : RateLimitDecision
    data class Blocked(val retryAfterMs: Long) : RateLimitDecision
}

class PairingRateLimiter(
    private val clock: MonotonicClock,
    private val maxRecords: Int = DEFAULT_MAX_RATE_LIMIT_RECORDS,
) {
    private data class Record(
        val failureCount: Int,
        val blockedUntilMs: Long?,
        val lastSeenMs: Long,
    )

    private val records = LinkedHashMap<String, Record>()

    val trackedSourceCount: Int
        @Synchronized get() = records.size

    init {
        require(maxRecords > 0)
    }

    @Synchronized
    fun check(sourceIpv4: String): RateLimitDecision {
        val now = now()
        val record = records[sourceIpv4] ?: return RateLimitDecision.Allowed(MAX_PAIRING_FAILURES)
        val blockedUntil = record.blockedUntilMs
        if (blockedUntil != null) {
            if (now < blockedUntil) {
                return RateLimitDecision.Blocked(blockedUntil - now)
            }
            records.remove(sourceIpv4)
            return RateLimitDecision.Allowed(MAX_PAIRING_FAILURES)
        }
        return RateLimitDecision.Allowed(MAX_PAIRING_FAILURES - record.failureCount)
    }

    @Synchronized
    fun recordFailure(sourceIpv4: String): RateLimitDecision {
        val now = now()
        val existing = records[sourceIpv4]
        if (existing?.blockedUntilMs != null && now < existing.blockedUntilMs) {
            return RateLimitDecision.Blocked(existing.blockedUntilMs - now)
        }
        if (existing?.blockedUntilMs != null) records.remove(sourceIpv4)

        ensureCapacityFor(sourceIpv4)
        val nextCount = (records[sourceIpv4]?.failureCount ?: 0) + 1
        return if (nextCount >= MAX_PAIRING_FAILURES) {
            val blockedUntil = Math.addExact(now, PAIRING_BLOCK_DURATION_MS)
            records[sourceIpv4] = Record(nextCount, blockedUntil, now)
            RateLimitDecision.Blocked(PAIRING_BLOCK_DURATION_MS)
        } else {
            records[sourceIpv4] = Record(nextCount, null, now)
            RateLimitDecision.Allowed(MAX_PAIRING_FAILURES - nextCount)
        }
    }

    @Synchronized
    fun recordSuccess(sourceIpv4: String) {
        records.remove(sourceIpv4)
    }

    @Synchronized
    fun clear() {
        records.clear()
    }

    private fun ensureCapacityFor(sourceIpv4: String) {
        if (sourceIpv4 in records || records.size < maxRecords) return
        val oldest = records.minByOrNull { it.value.lastSeenMs }?.key
        if (oldest != null) records.remove(oldest)
    }

    private fun now(): Long = clock.nowMs().also {
        require(it >= 0) { "Monotonic clock must not be negative" }
    }
}
