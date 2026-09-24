package ru.hznik.devicebridge.data.tls

import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec

/** In-memory [TlsKeyStore] for JVM tests, where AndroidKeyStore does not exist. */
class SoftwareTlsKeyStore : TlsKeyStore {
    private val keys = mutableMapOf<String, KeyPair>()
    val generatedAliases = mutableListOf<String>()

    override fun generate(alias: String, purpose: TlsKeyPurpose): PublicKey {
        val pair = KeyPairGenerator.getInstance("EC")
            .apply { initialize(ECGenParameterSpec("secp256r1")) }
            .generateKeyPair()
        keys[alias] = pair
        generatedAliases += alias
        return pair.public
    }

    override fun publicKey(alias: String): PublicKey? = keys[alias]?.public

    override fun privateKey(alias: String): PrivateKey? = keys[alias]?.private

    override fun signSha256WithEcdsa(alias: String, data: ByteArray): ByteArray {
        val key = privateKey(alias) ?: throw TlsMaterialException("Key $alias is missing")
        return Signature.getInstance("SHA256withECDSA").run {
            initSign(key)
            update(data)
            sign()
        }
    }

    override fun delete(alias: String) {
        keys.remove(alias)
    }
}
