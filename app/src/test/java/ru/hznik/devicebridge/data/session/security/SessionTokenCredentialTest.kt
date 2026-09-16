package ru.hznik.devicebridge.data.session.security

import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.hznik.devicebridge.domain.session.ServerGenerationId

class SessionTokenCredentialTest {

    @Test
    fun digestAcceptsExactTokenOnlyWithinItsGeneration() {
        val generation = ServerGenerationId(11)
        val credential = SessionTokenCredential.fromRaw(generation, "correct-token")

        assertTrue(credential.matches(generation, "correct-token"))
        assertFalse(credential.matches(generation, "correct-tokeN"))
        assertFalse(credential.matches(ServerGenerationId(12), "correct-token"))
        assertFalse(credential.matches(generation, ""))
    }

    @Test
    fun credentialStoresNoRawStringAndRejectsBlankInput() {
        val credential = SessionTokenCredential.fromRaw(
            ServerGenerationId(11),
            "never-store-this-raw-token",
        )

        val instanceFields = credential.javaClass.declaredFields
        assertFalse(instanceFields.any { it.type == String::class.java })
        assertTrue(instanceFields.any { it.type == ByteArray::class.java })
        assertThrows(IllegalArgumentException::class.java) {
            SessionTokenCredential.fromRaw(ServerGenerationId(11), " ")
        }
    }

    @Test
    fun verifierUsesConstantTimeDigestComparison() {
        val source = Files.readString(
            Path.of("src/main/java/ru/hznik/devicebridge/data/session/security/SessionTokenCredential.kt"),
        )

        assertTrue(source.contains("MessageDigest.isEqual"))
        assertFalse(source.contains("rawToken ="))
    }
}
