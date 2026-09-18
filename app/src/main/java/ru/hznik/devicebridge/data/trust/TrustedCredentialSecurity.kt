package ru.hznik.devicebridge.data.trust

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.inject.Inject
import javax.inject.Singleton

fun interface TrustedCredentialGenerator {
    fun generate(): String
}

class SecureRandomTrustedCredentialGenerator internal constructor(
    private val random: SecureRandom,
) : TrustedCredentialGenerator {
    @Inject
    constructor() : this(SecureRandom())

    override fun generate(): String {
        val bytes = ByteArray(CREDENTIAL_BYTES)
        random.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private companion object {
        const val CREDENTIAL_BYTES = 32
    }
}

interface TrustedCredentialVerifier {
    fun verifierFor(rawCredential: String): ByteArray

    fun matches(rawCredential: String, expectedVerifier: ByteArray): Boolean
}

fun interface TrustedHmacKeyProvider {
    fun get(): SecretKey
}

class HmacSha256TrustedCredentialVerifier @Inject constructor(
    private val keyProvider: TrustedHmacKeyProvider,
) : TrustedCredentialVerifier {
    override fun verifierFor(rawCredential: String): ByteArray {
        val mac = Mac.getInstance(HMAC_SHA_256)
        mac.init(keyProvider.get())
        return mac.doFinal(rawCredential.toByteArray(StandardCharsets.UTF_8))
    }

    override fun matches(
        rawCredential: String,
        expectedVerifier: ByteArray,
    ): Boolean = MessageDigest.isEqual(
        verifierFor(rawCredential),
        expectedVerifier,
    )

    private companion object {
        const val HMAC_SHA_256 = "HmacSHA256"
    }
}

@Singleton
class AndroidKeystoreTrustedHmacKeyProvider @Inject constructor() :
    TrustedHmacKeyProvider {
    override fun get(): SecretKey = synchronized(this) {
        val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey) ?: generateKey()
    }

    private fun generateKey(): SecretKey {
        val generator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_HMAC_SHA256,
            KEYSTORE_PROVIDER,
        )
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_SIGN,
            ).build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        const val KEY_ALIAS = "devicebridge_trusted_browser_hmac_v1"
    }
}
