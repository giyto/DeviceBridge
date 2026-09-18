package ru.hznik.devicebridge.data.trust

import java.security.SecureRandom
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TrustedCredentialSecurityTest {
    @Test
    fun generatedCredentialHasAtLeast256BitsAndIsUrlSafe() {
        val generator = SecureRandomTrustedCredentialGenerator(
            SecureRandom.getInstance("SHA1PRNG").apply { setSeed(42) },
        )

        val credential = generator.generate()

        assertTrue(credential.length >= 43)
        assertTrue(credential.matches(Regex("^[A-Za-z0-9_-]+$")))
    }

    @Test
    fun hmacVerifierAcceptsOnlyTheOriginalCredential() {
        val key = SecretKeySpec(ByteArray(32) { it.toByte() }, "HmacSHA256")
        val verifier = HmacSha256TrustedCredentialVerifier { key }
        val credential = "credential-value"
        val stored = verifier.verifierFor(credential)

        assertTrue(verifier.matches(credential, stored))
        assertFalse(verifier.matches("wrong-credential", stored))
        assertFalse(verifier.matches(credential, stored.copyOf().also { it[0] = 1 }))
        assertNotEquals(credential, stored.decodeToString())
    }
}
