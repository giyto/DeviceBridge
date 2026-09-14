package ru.hznik.devicebridge.diagnostics.server

import java.security.SecureRandom
import java.util.Base64

fun interface DiagnosticTokenGenerator {
    fun generate(): String
}

class SecureDiagnosticTokenGenerator(
    private val secureRandom: SecureRandom = SecureRandom(),
) : DiagnosticTokenGenerator {

    override fun generate(): String {
        val bytes = ByteArray(TOKEN_BYTES)
        secureRandom.nextBytes(bytes)
        return Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(bytes)
    }

    private companion object {
        const val TOKEN_BYTES = 32
    }
}
