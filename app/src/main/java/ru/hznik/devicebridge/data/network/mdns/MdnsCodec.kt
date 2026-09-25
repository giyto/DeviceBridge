package ru.hznik.devicebridge.data.network.mdns

import java.io.ByteArrayOutputStream

/** DNS record types and classes the local name responder deals with (RFC 1035, RFC 6762). */
object MdnsType {
    const val A = 1
    const val AAAA = 28
    const val NSEC = 47
    const val ANY = 255
}

const val MDNS_CLASS_IN = 1
private const val CLASS_ANY = 255
private const val TOP_BIT = 0x8000

data class MdnsQuestion(
    /** Lower-case name without the trailing dot, e.g. `devicebridge.local`. */
    val name: String,
    val type: Int,
    /** The QU bit: the asker wants a unicast answer. */
    val unicastResponse: Boolean = false,
)

class MdnsRecord(
    /** Lower-case name without the trailing dot. */
    val name: String,
    val type: Int,
    val cacheFlush: Boolean,
    val ttlSeconds: Long,
    val data: ByteArray,
) {
    override fun equals(other: Any?): Boolean =
        other is MdnsRecord && name == other.name && type == other.type &&
            cacheFlush == other.cacheFlush && ttlSeconds == other.ttlSeconds && data.contentEquals(other.data)

    override fun hashCode(): Int = (name.hashCode() * 31 + type) * 31 + data.contentHashCode()

    override fun toString(): String = "MdnsRecord($name, type=$type, ttl=$ttlSeconds)"
}

data class MdnsMessage(
    val id: Int = 0,
    val isResponse: Boolean,
    val questions: List<MdnsQuestion> = emptyList(),
    val answers: List<MdnsRecord> = emptyList(),
    val authorities: List<MdnsRecord> = emptyList(),
    val additionals: List<MdnsRecord> = emptyList(),
)

/**
 * Parses and builds the few mDNS messages DeviceBridge needs. Anything malformed or suspicious
 * decodes to `null`: the packet comes from anyone on the local network.
 */
object MdnsCodec {
    const val MAX_PACKET_BYTES = 9_000
    private const val HEADER_BYTES = 12
    private const val MAX_ENTRIES = 64
    private const val MAX_POINTER_JUMPS = 16
    private const val MAX_NAME_BYTES = 255
    private const val MAX_LABEL_BYTES = 63

    fun decode(packet: ByteArray, length: Int = packet.size): MdnsMessage? {
        if (length < HEADER_BYTES || length > MAX_PACKET_BYTES || length > packet.size) return null
        return try {
            Reader(packet, length).message()
        } catch (_: MalformedPacket) {
            null
        }
    }

    fun encode(message: MdnsMessage): ByteArray {
        val out = ByteArrayOutputStream()
        out.u16(message.id)
        out.u16(if (message.isResponse) 0x8400 else 0)
        out.u16(message.questions.size)
        out.u16(message.answers.size)
        out.u16(message.authorities.size)
        out.u16(message.additionals.size)
        message.questions.forEach { question ->
            out.name(question.name)
            out.u16(question.type)
            out.u16(MDNS_CLASS_IN or if (question.unicastResponse) TOP_BIT else 0)
        }
        (message.answers + message.authorities + message.additionals).forEach { record ->
            out.name(record.name)
            out.u16(record.type)
            out.u16(MDNS_CLASS_IN or if (record.cacheFlush) TOP_BIT else 0)
            out.u32(record.ttlSeconds)
            out.u16(record.data.size)
            out.write(record.data)
        }
        return out.toByteArray()
    }

    fun aRecord(name: String, address: ByteArray, ttlSeconds: Long, cacheFlush: Boolean = true): MdnsRecord {
        require(address.size == 4) { "IPv4 address expected" }
        return MdnsRecord(name.lowercase(), MdnsType.A, cacheFlush, ttlSeconds, address.copyOf())
    }

    /** NSEC record that says the name has an A record and nothing else, so no IPv6 either (RFC 6762 §6.1). */
    fun nsecOnlyA(name: String, ttlSeconds: Long, cacheFlush: Boolean = true): MdnsRecord {
        val data = ByteArrayOutputStream()
        data.name(name)
        // Window 0, one bitmap byte, bit for type 1 (A).
        data.write(byteArrayOf(0, 1, 0x40))
        return MdnsRecord(name.lowercase(), MdnsType.NSEC, cacheFlush, ttlSeconds, data.toByteArray())
    }

    private fun ByteArrayOutputStream.u16(value: Int) {
        write(value ushr 8 and 0xFF)
        write(value and 0xFF)
    }

    private fun ByteArrayOutputStream.u32(value: Long) {
        u16((value ushr 16 and 0xFFFF).toInt())
        u16((value and 0xFFFF).toInt())
    }

    private fun ByteArrayOutputStream.name(name: String) {
        name.trimEnd('.').split('.').filter { it.isNotEmpty() }.forEach { label ->
            val bytes = label.toByteArray(Charsets.US_ASCII)
            require(bytes.size in 1..MAX_LABEL_BYTES) { "Bad DNS label" }
            write(bytes.size)
            write(bytes)
        }
        write(0)
    }

    private class MalformedPacket : Exception() {
        override fun fillInStackTrace(): Throwable = this
    }

    private class Reader(private val bytes: ByteArray, private val length: Int) {
        private var position = 0

        fun message(): MdnsMessage {
            val id = u16()
            val flags = u16()
            val counts = IntArray(4) { u16() }
            if (counts.sum() > MAX_ENTRIES) throw MalformedPacket()
            val questions = List(counts[0]) {
                val name = name()
                val type = u16()
                val rawClass = u16()
                if (!isInternet(rawClass)) throw MalformedPacket()
                MdnsQuestion(name, type, unicastResponse = rawClass and TOP_BIT != 0)
            }
            val answers = List(counts[1]) { record() }
            val authorities = List(counts[2]) { record() }
            val additionals = List(counts[3]) { record() }
            return MdnsMessage(
                id = id,
                isResponse = flags and 0x8000 != 0,
                questions = questions,
                answers = answers.filterNotNull(),
                authorities = authorities.filterNotNull(),
                additionals = additionals.filterNotNull(),
            )
        }

        /** A record of another class is skipped (null), a broken one fails the whole packet. */
        private fun record(): MdnsRecord? {
            val name = name()
            val type = u16()
            val rawClass = u16()
            val ttl = u16().toLong() shl 16 or u16().toLong()
            val size = u16()
            if (position + size > length) throw MalformedPacket()
            val data = bytes.copyOfRange(position, position + size)
            position += size
            if (!isInternet(rawClass)) return null
            return MdnsRecord(name, type, rawClass and TOP_BIT != 0, ttl, data)
        }

        private fun isInternet(rawClass: Int): Boolean {
            val cls = rawClass and TOP_BIT.inv()
            return cls == MDNS_CLASS_IN || cls == CLASS_ANY
        }

        private fun name(): String {
            val labels = mutableListOf<String>()
            var cursor = position
            var jumps = 0
            var total = 0
            var resumeAt = -1
            while (true) {
                if (cursor >= length) throw MalformedPacket()
                val size = bytes[cursor].toInt() and 0xFF
                when {
                    size == 0 -> {
                        cursor += 1
                        break
                    }

                    size and 0xC0 == 0xC0 -> {
                        if (cursor + 1 >= length) throw MalformedPacket()
                        val target = (size and 0x3F) shl 8 or (bytes[cursor + 1].toInt() and 0xFF)
                        // Only backwards: a pointer to itself or forwards could loop.
                        if (target >= cursor || ++jumps > MAX_POINTER_JUMPS) throw MalformedPacket()
                        if (resumeAt < 0) resumeAt = cursor + 2
                        cursor = target
                    }

                    size and 0xC0 != 0 -> throw MalformedPacket()

                    else -> {
                        if (cursor + 1 + size > length) throw MalformedPacket()
                        total += size + 1
                        if (total > MAX_NAME_BYTES) throw MalformedPacket()
                        labels += String(bytes, cursor + 1, size, Charsets.ISO_8859_1)
                        cursor += 1 + size
                    }
                }
            }
            position = if (resumeAt >= 0) resumeAt else cursor
            return labels.joinToString(".").lowercase()
        }

        private fun u16(): Int {
            if (position + 2 > length) throw MalformedPacket()
            val value = (bytes[position].toInt() and 0xFF) shl 8 or (bytes[position + 1].toInt() and 0xFF)
            position += 2
            return value
        }
    }
}
