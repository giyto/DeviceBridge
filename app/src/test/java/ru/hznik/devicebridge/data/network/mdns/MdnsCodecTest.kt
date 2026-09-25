package ru.hznik.devicebridge.data.network.mdns

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MdnsCodecTest {

    @Test
    fun decodesWindowsStyleQueryForAAndAaaaWithCompression() {
        // Two questions; the second points back to the first name (offset 12).
        val packet = bytes(
            0x00, 0x00, 0x00, 0x00, 0x00, 0x02, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
            12, *ascii("DeviceBridge"), 5, *ascii("local"), 0, 0x00, 0x01, 0x00, 0x01,
            0xC0, 12, 0x00, 0x1C, 0x80, 0x01,
        )

        val message = requireNotNull(MdnsCodec.decode(packet))

        assertFalse(message.isResponse)
        assertEquals(
            listOf(
                MdnsQuestion("devicebridge.local", MdnsType.A, unicastResponse = false),
                MdnsQuestion("devicebridge.local", MdnsType.AAAA, unicastResponse = true),
            ),
            message.questions,
        )
    }

    @Test
    fun legacyUnicastQueryKeepsItsId() {
        val packet = bytes(
            0x12, 0x34, 0x01, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
            6, *ascii("nikita"), 5, *ascii("local"), 0, 0x00, 0x01, 0x00, 0x01,
        )

        val message = requireNotNull(MdnsCodec.decode(packet))

        assertEquals(0x1234, message.id)
        assertEquals("nikita.local", message.questions.single().name)
    }

    @Test
    fun responseRoundTripsWithAnswerAndNsec() {
        val answer = MdnsCodec.aRecord("devicebridge.local", byteArrayOf(192.toByte(), 168.toByte(), 1, 37), 120)
        val nsec = MdnsCodec.nsecOnlyA("devicebridge.local", 120)
        val encoded = MdnsCodec.encode(MdnsMessage(isResponse = true, answers = listOf(answer), additionals = listOf(nsec)))

        val decoded = requireNotNull(MdnsCodec.decode(encoded))

        assertTrue(decoded.isResponse)
        val a = decoded.answers.single()
        assertEquals("devicebridge.local", a.name)
        assertEquals(MdnsType.A, a.type)
        assertTrue(a.cacheFlush)
        assertEquals(120L, a.ttlSeconds)
        assertArrayEquals(byteArrayOf(192.toByte(), 168.toByte(), 1, 37), a.data)
        val n = decoded.additionals.single()
        assertEquals(MdnsType.NSEC, n.type)
        // Next name "devicebridge.local", then window 0 with only type A.
        assertArrayEquals(byteArrayOf(0, 1, 0x40), n.data.copyOfRange(n.data.size - 3, n.data.size))
    }

    @Test
    fun probeRoundTripsWithQuBitAndAuthority() {
        val probe = MdnsMessage(
            isResponse = false,
            questions = listOf(MdnsQuestion("devicebridge.local", MdnsType.ANY, unicastResponse = true)),
            authorities = listOf(MdnsCodec.aRecord("devicebridge.local", byteArrayOf(10, 0, 0, 5), 120, cacheFlush = false)),
        )

        val decoded = requireNotNull(MdnsCodec.decode(MdnsCodec.encode(probe)))

        assertTrue(decoded.questions.single().unicastResponse)
        assertEquals(MdnsType.ANY, decoded.questions.single().type)
        assertFalse(decoded.authorities.single().cacheFlush)
    }

    @Test
    fun truncatedPacketsAreRejectedWithoutExceptions() {
        val full = MdnsCodec.encode(
            MdnsMessage(isResponse = true, answers = listOf(MdnsCodec.aRecord("a.local", byteArrayOf(10, 0, 0, 1), 120))),
        )
        for (size in 0 until full.size) {
            assertNull("length $size", MdnsCodec.decode(full.copyOf(size)))
        }
    }

    @Test
    fun pointerLoopsAndForwardPointersAreRejected() {
        val selfLoop = bytes(0, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0xC0, 12, 0, 1, 0, 1)
        val forward = bytes(0, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0xC0, 20, 0, 1, 0, 1, 0, 0, 0)
        // Label, then a pointer back to that label: loops until the jump limit.
        val labelLoop = bytes(0, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0, 1, 'a'.code, 0xC0, 12, 0, 1, 0, 1)

        assertNull(MdnsCodec.decode(selfLoop))
        assertNull(MdnsCodec.decode(forward))
        assertNull(MdnsCodec.decode(labelLoop))
    }

    @Test
    fun hugeCountsAndOversizedPacketsAreRejected() {
        val hugeCounts = bytes(0, 0, 0, 0, 0xFF, 0xFF, 0xFF, 0xFF, 0, 0, 0, 0)
        val oversized = ByteArray(MdnsCodec.MAX_PACKET_BYTES + 1)

        assertNull(MdnsCodec.decode(hugeCounts))
        assertNull(MdnsCodec.decode(oversized))
    }

    @Test
    fun recordLengthPastTheEndIsRejected() {
        val packet = bytes(
            0, 0, 0x84, 0, 0, 0, 0, 1, 0, 0, 0, 0,
            1, 'a'.code, 0, 0, 1, 0, 1, 0, 0, 0, 120, 0x00, 0x40, 10, 0, 0, 1,
        )
        assertNull(MdnsCodec.decode(packet))
    }

    @Test
    fun nameLongerThanTheDnsLimitIsRejected() {
        val labels = (1..5).flatMap { listOf(60) + List(60) { 'a'.code } }
        val packet = bytes(0, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0, *labels.toIntArray(), 0, 0, 1, 0, 1)
        assertNull(MdnsCodec.decode(packet))
    }

    private fun ascii(value: String): IntArray = value.map { it.code }.toIntArray()

    private fun bytes(vararg values: Int): ByteArray = ByteArray(values.size) { values[it].toByte() }
}
