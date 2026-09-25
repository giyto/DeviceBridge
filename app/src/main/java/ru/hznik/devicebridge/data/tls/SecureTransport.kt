package ru.hznik.devicebridge.data.tls

import java.net.Inet4Address

/** Whether the server should serve browsers over HTTPS, and the certificates to do it with. */
interface SecureTransport {
    fun isEnabled(): Boolean

    /** Whether the phone's root lets the server go by [localName], e.g. `nikita.local`. */
    fun permitsName(localName: String): Boolean

    fun serverMaterial(address: Inet4Address, localName: String? = null): ServerTlsMaterial

    object Disabled : SecureTransport {
        override fun isEnabled(): Boolean = false

        override fun permitsName(localName: String): Boolean = false

        override fun serverMaterial(address: Inet4Address, localName: String?): ServerTlsMaterial =
            throw TlsMaterialException("Secure mode is off")
    }
}

class LocalCertificateSecureTransport(
    private val enabled: () -> Boolean,
    private val authority: LocalCertificateAuthority,
) : SecureTransport {
    override fun isEnabled(): Boolean = enabled()

    override fun permitsName(localName: String): Boolean = authority.permits(localName)

    override fun serverMaterial(address: Inet4Address, localName: String?): ServerTlsMaterial =
        authority.serverMaterial(address, localName)
}
