package ru.hznik.devicebridge.data.session.security

import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionSecretGeneratorTest {

    @Test
    fun pairingCodeUsesBoundedRandomAndPreservesLeadingZeros() {
        val random = FakeCryptographicRandom(nextIntValue = 42)

        val code = SessionSecretGenerator(random).newPairingCode()

        assertEquals("000042", code)
        assertEquals(listOf(1_000_000), random.requestedBounds)
    }

    @Test
    fun tokenContainsThirtyTwoRandomBytesEncodedAsBase64UrlWithoutPadding() {
        val bytes = ByteArray(32) { it.toByte() }
        val random = FakeCryptographicRandom(bytes = bytes)

        val token = SessionSecretGenerator(random).newSessionToken()

        assertEquals("AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8", token)
        assertEquals(listOf(32), random.requestedByteCounts)
        assertEquals(43, token.length)
        assertFalse(token.contains('='))
        assertFalse(token.contains('+'))
        assertFalse(token.contains('/'))
    }

    @Test
    fun implementationUsesBoundedApiInsteadOfModuloReduction() {
        val source = Files.readString(
            Path.of("src/main/java/ru/hznik/devicebridge/data/session/security/SessionSecretGenerator.kt"),
        )

        assertTrue(source.contains("nextInt(PAIRING_CODE_BOUND)"))
        assertFalse(source.contains(" % "))
    }

    private class FakeCryptographicRandom(
        private val nextIntValue: Int = 0,
        private val bytes: ByteArray = ByteArray(32),
    ) : CryptographicRandom {
        val requestedBounds = mutableListOf<Int>()
        val requestedByteCounts = mutableListOf<Int>()

        override fun nextInt(bound: Int): Int {
            requestedBounds += bound
            return nextIntValue
        }

        override fun nextBytes(size: Int): ByteArray {
            requestedByteCounts += size
            return bytes.copyOf(size)
        }
    }
}
