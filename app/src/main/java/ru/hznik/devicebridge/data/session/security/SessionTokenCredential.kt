package ru.hznik.devicebridge.data.session.security

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import ru.hznik.devicebridge.domain.session.ServerGenerationId

class SessionTokenCredential private constructor(
    val generationId: ServerGenerationId,
    digest: ByteArray,
) {
    private val digest: ByteArray = digest.copyOf()

    fun matches(
        expectedGenerationId: ServerGenerationId,
        token: String,
    ): Boolean {
        if (generationId != expectedGenerationId || token.isBlank()) return false
        val candidateDigest = sha256(token)
        return MessageDigest.isEqual(digest, candidateDigest)
    }

    companion object {
        fun fromRaw(
            generationId: ServerGenerationId,
            token: String,
        ): SessionTokenCredential {
            require(token.isNotBlank()) { "Session token must not be blank" }
            require(token.length <= 256) { "Session token is too long" }
            return SessionTokenCredential(generationId, sha256(token))
        }

        private fun sha256(value: String): ByteArray = MessageDigest
            .getInstance("SHA-256")
            .digest(value.toByteArray(StandardCharsets.UTF_8))
    }
}
