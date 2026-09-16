package ru.hznik.devicebridge.data.session

import ru.hznik.devicebridge.data.server.MonotonicClock

const val MAX_ACTIVE_CHALLENGES = 64
const val MAX_CONCURRENT_PENDING_REQUESTS = 8

enum class RegistryPutResult {
    ADDED,
    CAPACITY_REACHED,
}

class BoundedExpiringRegistry<K : Any, V : Any>(
    private val clock: MonotonicClock,
    private val maxEntries: Int,
) {
    private data class Entry<V>(
        val value: V,
        val expiresAtMs: Long,
    )

    private val entries = LinkedHashMap<K, Entry<V>>()

    val size: Int
        @Synchronized get() {
            pruneExpired()
            return entries.size
        }

    init {
        require(maxEntries > 0)
    }

    @Synchronized
    fun put(key: K, value: V, expiresAtMs: Long): RegistryPutResult {
        val now = checkedNow()
        require(expiresAtMs > now) { "Entry must expire in the future" }
        pruneExpired(now)
        if (key !in entries && entries.size >= maxEntries) {
            return RegistryPutResult.CAPACITY_REACHED
        }
        entries[key] = Entry(value, expiresAtMs)
        return RegistryPutResult.ADDED
    }

    @Synchronized
    fun get(key: K): V? {
        val now = checkedNow()
        val entry = entries[key] ?: return null
        if (entry.expiresAtMs <= now) {
            entries.remove(key)
            return null
        }
        return entry.value
    }

    @Synchronized
    fun remove(key: K): V? = entries.remove(key)?.value

    @Synchronized
    fun values(): List<V> {
        pruneExpired()
        return entries.values.map(Entry<V>::value)
    }

    @Synchronized
    fun clear(): List<V> {
        val removed = entries.values.map(Entry<V>::value)
        entries.clear()
        return removed
    }

    @Synchronized
    fun pruneExpired(): List<V> = pruneExpired(checkedNow())

    private fun pruneExpired(now: Long): List<V> {
        val expired = entries.filterValues { it.expiresAtMs <= now }
        expired.keys.forEach(entries::remove)
        return expired.values.map(Entry<V>::value)
    }

    private fun checkedNow(): Long = clock.nowMs().also {
        require(it >= 0) { "Monotonic clock must not be negative" }
    }
}
