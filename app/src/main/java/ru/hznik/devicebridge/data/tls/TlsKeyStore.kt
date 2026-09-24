package ru.hznik.devicebridge.data.tls

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import javax.inject.Inject

/** EC P-256 keys that never leave the store; callers only get the public half and signatures. */
interface TlsKeyStore {
    /** Creates a new key under [alias], replacing any previous one, and returns its public key. */
    fun generate(alias: String, purpose: TlsKeyPurpose): PublicKey

    fun publicKey(alias: String): PublicKey?

    /** A handle that TLS can use for the handshake; its bytes are not readable. */
    fun privateKey(alias: String): PrivateKey?

    fun signSha256WithEcdsa(alias: String, data: ByteArray): ByteArray

    fun delete(alias: String)
}

enum class TlsKeyPurpose {
    /** Signs certificates only. */
    CERTIFICATE_AUTHORITY,

    /** Signs TLS handshakes, whose digests depend on what the browser offers. */
    SERVER,
}

class AndroidTlsKeyStore @Inject constructor() : TlsKeyStore {

    private val keyStore: KeyStore by lazy {
        KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
    }

    override fun generate(alias: String, purpose: TlsKeyPurpose): PublicKey {
        delete(alias)
        val builder = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN)
            .setAlgorithmParameterSpec(ECGenParameterSpec(CURVE))
        // Listed in each call rather than through an array, so lint can check the constants.
        val spec = when (purpose) {
            TlsKeyPurpose.CERTIFICATE_AUTHORITY -> builder.setDigests(KeyProperties.DIGEST_SHA256)
            TlsKeyPurpose.SERVER -> builder.setDigests(
                KeyProperties.DIGEST_NONE,
                KeyProperties.DIGEST_SHA1,
                KeyProperties.DIGEST_SHA256,
                KeyProperties.DIGEST_SHA384,
                KeyProperties.DIGEST_SHA512,
            )
        }.build()
        return KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, ANDROID_KEY_STORE)
            .apply { initialize(spec) }
            .generateKeyPair()
            .public
    }

    override fun publicKey(alias: String): PublicKey? =
        keyStore.getCertificate(alias)?.publicKey

    override fun privateKey(alias: String): PrivateKey? =
        keyStore.getKey(alias, null) as? PrivateKey

    override fun signSha256WithEcdsa(alias: String, data: ByteArray): ByteArray {
        val key = privateKey(alias) ?: throw TlsMaterialException("Key $alias is missing")
        return Signature.getInstance(SIGNATURE_ALGORITHM).run {
            initSign(key)
            update(data)
            sign()
        }
    }

    override fun delete(alias: String) {
        if (keyStore.containsAlias(alias)) keyStore.deleteEntry(alias)
    }

    private companion object {
        const val ANDROID_KEY_STORE = "AndroidKeyStore"
        const val CURVE = "secp256r1"
        const val SIGNATURE_ALGORITHM = "SHA256withECDSA"
    }
}

class TlsMaterialException(message: String, cause: Throwable? = null) : Exception(message, cause)
