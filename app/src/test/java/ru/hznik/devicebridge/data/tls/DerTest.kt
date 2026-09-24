package ru.hznik.devicebridge.data.tls

import java.math.BigInteger
import java.time.Instant
import javax.security.auth.x500.X500Principal
import org.ietf.jgss.Oid
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class DerTest {

    @Test
    fun oidsMatchTheJdkEncoding() {
        listOf(
            "1.2.840.10045.2.1",
            "1.2.840.10045.3.1.7",
            "1.2.840.10045.4.3.2",
            "2.5.29.30",
            "1.3.6.1.5.5.7.3.1",
            "2.999.3",
        ).forEach { oid ->
            assertArrayEquals(oid, Oid(oid).der, Der.oid(oid))
        }
    }

    @Test
    fun integersAreMinimalTwosComplement() {
        assertArrayEquals(hex("020100"), Der.integer(0))
        assertArrayEquals(hex("02017f"), Der.integer(127))
        assertArrayEquals(hex("02020080"), Der.integer(128))
        assertArrayEquals(hex("0202ff7f"), Der.integer(-129))
        assertArrayEquals(
            hex("021100ffffffffffffffffffffffffffffffff"),
            Der.integer(BigInteger(ByteArray(16) { 0xFF.toByte() }.let { byteArrayOf(0) + it })),
        )
    }

    @Test
    fun lengthsUseShortAndLongForms() {
        assertArrayEquals(hex("047f") + ByteArray(127), Der.octetString(ByteArray(127)))
        assertArrayEquals(hex("048180") + ByteArray(128), Der.octetString(ByteArray(128)))
        assertArrayEquals(hex("0482012c") + ByteArray(300), Der.octetString(ByteArray(300)))
    }

    @Test
    fun namedBitsDropTrailingZeroBits() {
        // keyCertSign (5) and cRLSign (6), then digitalSignature (0).
        assertArrayEquals(hex("03020106"), Der.namedBits(5, 6))
        assertArrayEquals(hex("03020780"), Der.namedBits(0))
        assertArrayEquals(hex("0303070080"), Der.namedBits(8))
    }

    @Test
    fun bitStringsOfWholeBytesHaveNoUnusedBits() {
        assertArrayEquals(hex("030300abcd"), Der.bitString(hex("abcd")))
    }

    @Test
    fun timesSwitchToGeneralizedTimeIn2050() {
        assertArrayEquals(
            "\u0017\u000d250102030405Z".toByteArray(Charsets.US_ASCII),
            Der.time(Instant.parse("2025-01-02T03:04:05.678Z")),
        )
        assertArrayEquals(
            "\u0018\u000f20500101000000Z".toByteArray(Charsets.US_ASCII),
            Der.time(Instant.parse("2050-01-01T00:00:00Z")),
        )
    }

    @Test
    fun booleansAndContextTags() {
        assertArrayEquals(hex("0101ff"), Der.boolean(true))
        assertArrayEquals(hex("a003020102"), Der.explicit(0, Der.integer(2)))
        assertArrayEquals(hex("8704c0a80000"), Der.implicitPrimitive(7, hex("c0a80000")))
        assertArrayEquals(
            hex("a006020101020102"),
            Der.implicitConstructed(0, listOf(Der.integer(1), Der.integer(2))),
        )
    }

    @Test
    fun setMembersAreSortedByEncoding() {
        assertArrayEquals(hex("3106020101020102"), Der.set(Der.integer(2), Der.integer(1)))
    }

    @Test
    fun distinguishedNamesParseInTheJdk() {
        val name = Der.sequence(
            Der.set(Der.sequence(Der.oid("2.5.4.10"), Der.utf8String("DeviceBridge"))),
            Der.set(Der.sequence(Der.oid("2.5.4.3"), Der.utf8String("Корень DeviceBridge"))),
        )
        assertEquals("CN=Корень DeviceBridge,O=DeviceBridge", X500Principal(name).name)
    }

    private fun hex(value: String): ByteArray =
        value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
