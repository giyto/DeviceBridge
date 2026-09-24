package ru.hznik.devicebridge.data.tls

import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * The few DER encodings a certificate needs. Every function returns one complete TLV, so
 * values nest by passing the result of one call into another.
 */
internal object Der {
    fun sequence(vararg elements: ByteArray): ByteArray = tlv(TAG_SEQUENCE, concat(elements))

    fun sequence(elements: List<ByteArray>): ByteArray = tlv(TAG_SEQUENCE, concat(elements))

    fun set(vararg elements: ByteArray): ByteArray =
        tlv(TAG_SET, concat(elements.sortedWith(::compareUnsigned)))

    fun boolean(value: Boolean): ByteArray =
        tlv(TAG_BOOLEAN, byteArrayOf(if (value) 0xFF.toByte() else 0))

    fun integer(value: Long): ByteArray = integer(BigInteger.valueOf(value))

    fun integer(value: BigInteger): ByteArray = tlv(TAG_INTEGER, value.toByteArray())

    fun oid(dotted: String): ByteArray {
        val arcs = dotted.split('.').map(String::toLong)
        require(arcs.size >= 2 && arcs[0] in 0..2 && arcs.all { it >= 0 }) { "Bad OID $dotted" }
        val body = ByteArrayOutputStream()
        writeBase128(body, arcs[0] * 40 + arcs[1])
        arcs.drop(2).forEach { writeBase128(body, it) }
        return tlv(TAG_OID, body.toByteArray())
    }

    /** A bit string whose bits fill whole bytes, such as a key or a signature. */
    fun bitString(bytes: ByteArray): ByteArray = tlv(TAG_BIT_STRING, byteArrayOf(0) + bytes)

    /**
     * A named bit list such as KeyUsage, where bit 0 is the most significant bit of the first
     * byte. Trailing zero bits are dropped, as DER requires.
     */
    fun namedBits(vararg bits: Int): ByteArray {
        require(bits.isNotEmpty() && bits.all { it >= 0 })
        val bytes = ByteArray(bits.max() / 8 + 1)
        bits.forEach { bit ->
            bytes[bit / 8] = (bytes[bit / 8].toInt() or (0x80 ushr (bit % 8))).toByte()
        }
        val unused = Integer.numberOfTrailingZeros(bytes.last().toInt() and 0xFF)
        return tlv(TAG_BIT_STRING, byteArrayOf(unused.toByte()) + bytes)
    }

    fun octetString(bytes: ByteArray): ByteArray = tlv(TAG_OCTET_STRING, bytes)

    fun utf8String(value: String): ByteArray = tlv(TAG_UTF8_STRING, value.toByteArray(Charsets.UTF_8))

    /** UTCTime through 2049 and GeneralizedTime after, as RFC 5280 requires for validity dates. */
    fun time(instant: Instant): ByteArray {
        val utc = instant.atOffset(ZoneOffset.UTC).withNano(0)
        return if (utc.year in 1950..2049) {
            tlv(TAG_UTC_TIME, UTC_TIME.format(utc).toByteArray(Charsets.US_ASCII))
        } else {
            tlv(TAG_GENERALIZED_TIME, GENERALIZED_TIME.format(utc).toByteArray(Charsets.US_ASCII))
        }
    }

    /** `[tag] EXPLICIT`: the whole inner TLV wrapped in a constructed context tag. */
    fun explicit(tag: Int, inner: ByteArray): ByteArray = tlv(CONTEXT_CONSTRUCTED or tag, inner)

    /** `[tag] IMPLICIT` for a primitive type: the content bytes under a context tag. */
    fun implicitPrimitive(tag: Int, content: ByteArray): ByteArray = tlv(CONTEXT_PRIMITIVE or tag, content)

    /** `[tag] IMPLICIT` for a constructed type such as `SEQUENCE OF`: its elements under a context tag. */
    fun implicitConstructed(tag: Int, elements: List<ByteArray>): ByteArray =
        tlv(CONTEXT_CONSTRUCTED or tag, concat(elements))

    fun tlv(tag: Int, content: ByteArray): ByteArray {
        require(tag in 0..0xFF) { "Only single-byte tags are supported" }
        return byteArrayOf(tag.toByte()) + length(content.size) + content
    }

    private fun length(size: Int): ByteArray {
        if (size < 0x80) return byteArrayOf(size.toByte())
        val bytes = BigInteger.valueOf(size.toLong()).toByteArray().dropWhile { it == 0.toByte() }
        return byteArrayOf((0x80 or bytes.size).toByte()) + bytes.toByteArray()
    }

    private fun writeBase128(out: ByteArrayOutputStream, value: Long) {
        val groups = ArrayList<Int>()
        var rest = value
        do {
            groups += (rest and 0x7F).toInt()
            rest = rest ushr 7
        } while (rest != 0L)
        for (index in groups.indices.reversed()) {
            out.write(if (index == 0) groups[index] else groups[index] or 0x80)
        }
    }

    private fun concat(parts: Array<out ByteArray>): ByteArray = concat(parts.asList())

    private fun concat(parts: List<ByteArray>): ByteArray {
        val out = ByteArrayOutputStream(parts.sumOf { it.size })
        parts.forEach(out::write)
        return out.toByteArray()
    }

    /** DER orders the members of a SET by their encodings. */
    private fun compareUnsigned(left: ByteArray, right: ByteArray): Int {
        for (index in 0 until minOf(left.size, right.size)) {
            val diff = (left[index].toInt() and 0xFF) - (right[index].toInt() and 0xFF)
            if (diff != 0) return diff
        }
        return left.size - right.size
    }

    private const val TAG_BOOLEAN = 0x01
    private const val TAG_INTEGER = 0x02
    private const val TAG_BIT_STRING = 0x03
    private const val TAG_OCTET_STRING = 0x04
    private const val TAG_OID = 0x06
    private const val TAG_UTF8_STRING = 0x0C
    private const val TAG_SEQUENCE = 0x30
    private const val TAG_SET = 0x31
    private const val TAG_UTC_TIME = 0x17
    private const val TAG_GENERALIZED_TIME = 0x18
    private const val CONTEXT_PRIMITIVE = 0x80
    private const val CONTEXT_CONSTRUCTED = 0xA0

    private val UTC_TIME = DateTimeFormatter.ofPattern("yyMMddHHmmss'Z'")
    private val GENERALIZED_TIME = DateTimeFormatter.ofPattern("yyyyMMddHHmmss'Z'")
}
