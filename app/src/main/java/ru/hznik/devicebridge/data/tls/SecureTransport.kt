package ru.hznik.devicebridge.data.tls

import java.net.Inet4Address

/** Whether the server should serve browsers over HTTPS, and the certificates to do it with. */
interface SecureTransport {
    fun isEnabled(): Boolean

    fun serverMaterial(address: Inet4Address): ServerTlsMaterial

    object Disabled : SecureTransport {
        override fun isEnabled(): Boolean = false

        override fun serverMaterial(address: Inet4Address): ServerTlsMaterial =
            throw TlsMaterialException("Secure mode is off")
    }
}

class LocalCertificateSecureTransport(
    private val enabled: () -> Boolean,
    private val authority: LocalCertificateAuthority,
) : SecureTransport {
    override fun isEnabled(): Boolean = enabled()

    override fun serverMaterial(address: Inet4Address): ServerTlsMaterial =
        authority.serverMaterial(address)
}
