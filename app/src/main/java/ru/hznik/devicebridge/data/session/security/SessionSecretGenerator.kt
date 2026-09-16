package ru.hznik.devicebridge.data.session.security

import java.security.SecureRandom
import java.util.Base64

internal const val PAIRING_CODE_BOUND = 1_000_000
internal const val SESSION_TOKEN_BYTES = 32

interface CryptographicRandom {
    fun nextInt(bound: Int): Int

    fun nextBytes(size: Int): ByteArray
}

class JavaCryptographicRandom(
    private val delegate: SecureRandom = SecureRandom(),
) : CryptographicRandom {
    override fun nextInt(bound: Int): Int = delegate.nextInt(bound)

    override fun nextBytes(size: Int): ByteArray {
        require(size > 0)
        return ByteArray(size).also(delegate::nextBytes)
    }
}

class SessionSecretGenerator(
    private val random: CryptographicRandom,
) {
    fun newPairingCode(): String = random
        .nextInt(PAIRING_CODE_BOUND)
        .toString()
        .padStart(length = 6, padChar = '0')

    fun newSessionToken(): String = Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(random.nextBytes(SESSION_TOKEN_BYTES))

    fun newOpaqueId(): String = newSessionToken()
}
