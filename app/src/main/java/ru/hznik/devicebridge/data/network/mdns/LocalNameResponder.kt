package ru.hznik.devicebridge.data.network.mdns

import kotlinx.coroutines.delay

/** Where the responder sends a packet. */
sealed interface MdnsDestination {
    /** 224.0.0.251:5353 on the endpoint's interface. */
    data object Multicast : MdnsDestination

    data class Unicast(val address: ByteArray, val port: Int) : MdnsDestination {
        override fun equals(other: Any?): Boolean =
            other is Unicast && address.contentEquals(other.address) && port == other.port

        override fun hashCode(): Int = address.contentHashCode() * 31 + port
    }
}

fun interface MdnsSender {
    fun send(packet: ByteArray, destination: MdnsDestination)
}

/** The IPv4 subnet of the endpoint's interface, for the RFC 6762 §11 source check. */
class Ipv4Subnet(address: ByteArray, prefixLength: Int) {
    private val mask: Int = if (prefixLength <= 0) 0 else -1 shl (32 - prefixLength.coerceAtMost(32))
    private val network: Int = address.toInt() and mask

    fun contains(address: ByteArray): Boolean = address.size == 4 && address.toInt() and mask == network
}

sealed interface LocalNameClaim {
    /** [name] is the full name, e.g. `devicebridge-2.local`. */
    data class Claimed(val name: String, val requestedTaken: Boolean) : LocalNameClaim

    data object AllTaken : LocalNameClaim
}

/**
 * Answers mDNS questions about one name (`<label>.local`) with the server's IPv4 address.
 * It checks the name is free before using it, answers only A (and "no AAAA"), announces the
 * name, gives it up on a conflict and says goodbye on stop. It never advertises services.
 *
 * Packets arrive through [onPacket] from the socket thread; the suspend functions run on the
 * server's scope.
 */
class LocalNameResponder(
    private val sender: MdnsSender,
    address: ByteArray,
    private val subnet: Ipv4Subnet,
    private val nowMs: () -> Long,
    private val onNameLost: () -> Unit = {},
) {
    private val address: ByteArray = address.copyOf()
    private val lock = Any()
    private var probing: String? = null
    private var probeConflict = false
    private var claimed: String? = null
    private var lost = false
    private val lastMulticastMs = mutableMapOf<Int, Long>()

    val currentName: String?
        get() = synchronized(lock) { claimed?.takeUnless { lost } }

    suspend fun claim(requestedLabel: String): LocalNameClaim {
        val label = requestedLabel.lowercase()
        val candidates = listOf(label) + (2..MAX_SUFFIX).map { "$label-$it" }
        candidates.forEachIndexed { index, candidate ->
            val name = "$candidate.local"
            if (probe(name)) {
                synchronized(lock) {
                    claimed = name
                    lost = false
                }
                return LocalNameClaim.Claimed(name, requestedTaken = index > 0)
            }
        }
        return LocalNameClaim.AllTaken
    }

    suspend fun announce() {
        repeat(ANNOUNCEMENTS) { index ->
            val name = currentName ?: return
            if (index > 0) delay(ANNOUNCE_INTERVAL_MS)
            sender.send(response(name, ttl = TTL_SECONDS), MdnsDestination.Multicast)
            markMulticast(MdnsType.A)
            markMulticast(MdnsType.NSEC)
        }
    }

    /** Tells the network the name is gone. Safe to call more than once. */
    fun release() {
        val name = synchronized(lock) {
            val current = claimed?.takeUnless { lost }
            claimed = null
            current
        } ?: return
        sender.send(response(name, ttl = 0), MdnsDestination.Multicast)
    }

    fun onPacket(packet: ByteArray, length: Int, sourceAddress: ByteArray, sourcePort: Int) {
        // Our own multicast comes back to us from port 5353; a local client on another port is
        // a real asker.
        val ownEcho = sourceAddress.contentEquals(address) && sourcePort == MDNS_PORT
        if (ownEcho || !subnet.contains(sourceAddress)) return
        val message = MdnsCodec.decode(packet, length) ?: return
        if (message.isResponse) onResponse(message) else onQuery(message, sourceAddress, sourcePort)
    }

    private suspend fun probe(name: String): Boolean {
        synchronized(lock) {
            probing = name
            probeConflict = false
        }
        val query = MdnsCodec.encode(
            MdnsMessage(
                isResponse = false,
                questions = listOf(MdnsQuestion(name, MdnsType.ANY, unicastResponse = true)),
                authorities = listOf(MdnsCodec.aRecord(name, address, TTL_SECONDS, cacheFlush = false)),
            ),
        )
        repeat(PROBES) {
            sender.send(query, MdnsDestination.Multicast)
            delay(PROBE_INTERVAL_MS)
            if (synchronized(lock) { probeConflict }) return finishProbe(false)
        }
        return finishProbe(!synchronized(lock) { probeConflict })
    }

    private fun finishProbe(free: Boolean): Boolean {
        synchronized(lock) { probing = null }
        return free
    }

    private fun onResponse(message: MdnsMessage) {
        val records = message.answers + message.additionals
        synchronized(lock) {
            val probed = probing
            if (probed != null && records.any { it.name == probed && it.isOtherAddress() }) {
                probeConflict = true
            }
            val name = claimed
            if (name != null && !lost && records.any { it.name == name && it.isOtherAddress() }) {
                lost = true
            } else {
                return
            }
        }
        onNameLost()
    }

    private fun onQuery(message: MdnsMessage, sourceAddress: ByteArray, sourcePort: Int) {
        synchronized(lock) {
            val probed = probing
            // Someone else probes the same name at the same time (RFC 6762 §8.2).
            if (probed != null && message.questions.any { it.name == probed }) {
                val theirs = message.authorities.firstOrNull { it.name == probed && it.type == MdnsType.A }
                if (theirs != null && compareUnsigned(theirs.data, address) > 0) probeConflict = true
            }
        }
        val name = currentName ?: return
        val asked = message.questions.filter { it.name == name }
        if (asked.isEmpty()) return
        val wantsA = asked.any { it.type == MdnsType.A || it.type == MdnsType.ANY }
        val wantsNsec = asked.any { it.type == MdnsType.AAAA || it.type == MdnsType.ANY }
        if (!wantsA && !wantsNsec) return
        val knownA = message.answers.any {
            it.name == name && it.type == MdnsType.A && it.data.contentEquals(address) && it.ttlSeconds >= TTL_SECONDS / 2
        }

        if (sourcePort != MDNS_PORT) {
            // Legacy unicast: a plain DNS client wants a normal DNS answer on its own port.
            sender.send(
                response(name, TTL_LEGACY_SECONDS, wantsA, wantsNsec, id = message.id, questions = asked, cacheFlush = false),
                MdnsDestination.Unicast(sourceAddress.copyOf(), sourcePort),
            )
            return
        }
        val sendA = wantsA && !knownA
        if (!sendA && !wantsNsec) return
        if (asked.any { it.unicastResponse }) {
            // Also works when the asker's firewall drops multicast answers.
            sender.send(response(name, TTL_SECONDS, sendA, wantsNsec), MdnsDestination.Unicast(sourceAddress.copyOf(), MDNS_PORT))
        }
        val now = nowMs()
        val multicastA = sendA && canMulticast(MdnsType.A, now)
        val multicastNsec = wantsNsec && canMulticast(MdnsType.NSEC, now)
        if (multicastA || multicastNsec) {
            sender.send(response(name, TTL_SECONDS, multicastA, multicastNsec), MdnsDestination.Multicast)
            if (multicastA) markMulticast(MdnsType.A, now)
            if (multicastNsec) markMulticast(MdnsType.NSEC, now)
        }
    }

    private fun canMulticast(type: Int, now: Long): Boolean = synchronized(lock) {
        val last = lastMulticastMs[type] ?: return@synchronized true
        now - last >= MULTICAST_MIN_INTERVAL_MS
    }

    private fun markMulticast(type: Int, now: Long = nowMs()) {
        synchronized(lock) { lastMulticastMs[type] = now }
    }

    private fun response(
        name: String,
        ttl: Long,
        includeA: Boolean = true,
        includeNsec: Boolean = true,
        id: Int = 0,
        questions: List<MdnsQuestion> = emptyList(),
        cacheFlush: Boolean = true,
    ): ByteArray {
        val a = MdnsCodec.aRecord(name, address, ttl, cacheFlush)
        val nsec = MdnsCodec.nsecOnlyA(name, ttl, cacheFlush)
        val answers = buildList {
            if (includeA) add(a)
            if (includeNsec && !includeA) add(nsec)
        }
        // An A answer always carries "no IPv6" so the asker does not wait for AAAA (RFC 6762 §6.1).
        val additionals = if (includeA) listOf(nsec) else emptyList()
        return MdnsCodec.encode(
            MdnsMessage(
                id = id,
                isResponse = true,
                questions = questions.map { it.copy(unicastResponse = false) },
                answers = answers,
                additionals = additionals,
            ),
        )
    }

    private fun MdnsRecord.isOtherAddress(): Boolean = type == MdnsType.A && !data.contentEquals(address)

    companion object {
        const val MDNS_PORT = 5353
        const val MAX_SUFFIX = 9
        const val PROBES = 3
        const val PROBE_INTERVAL_MS = 250L
        const val ANNOUNCEMENTS = 2
        const val ANNOUNCE_INTERVAL_MS = 1_000L
        const val TTL_SECONDS = 120L
        const val TTL_LEGACY_SECONDS = 10L
        const val MULTICAST_MIN_INTERVAL_MS = 1_000L
    }
}

private fun ByteArray.toInt(): Int =
    (this[0].toInt() and 0xFF shl 24) or (this[1].toInt() and 0xFF shl 16) or
        (this[2].toInt() and 0xFF shl 8) or (this[3].toInt() and 0xFF)

private fun compareUnsigned(left: ByteArray, right: ByteArray): Int {
    for (index in 0 until minOf(left.size, right.size)) {
        val difference = (left[index].toInt() and 0xFF) - (right[index].toInt() and 0xFF)
        if (difference != 0) return difference
    }
    return left.size - right.size
}
