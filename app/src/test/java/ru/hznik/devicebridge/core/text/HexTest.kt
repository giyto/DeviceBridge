package ru.hznik.devicebridge.core.text

import org.junit.Assert.assertEquals
import org.junit.Test

class HexTest {
    @Test
    fun encodesEveryByteAsTwoLowercaseDigits() {
        val bytes = ByteArray(256) { it.toByte() }

        assertEquals(bytes.joinToString("") { "%02x".format(it) }, bytes.toLowerHex())
    }

    @Test
    fun hashesUtf8Text() {
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            "".sha256Hex(),
        )
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            "abc".sha256Hex(),
        )
    }
}
