package ru.hznik.devicebridge.data.tls

import java.security.MessageDigest
import java.security.cert.X509Certificate

/** Fingerprints of a certificate, formatted the way people compare them on screen. */
data class CertificateFingerprints(
    /** SHA-1 in groups of eight, as the Windows install warning shows its thumbprint. */
    val sha1: String,
    /** SHA-256 in pairs, as browser certificate viewers show it. */
    val sha256: String,
) {
    companion object {
        fun of(certificate: X509Certificate): CertificateFingerprints = of(certificate.encoded)

        fun of(encoded: ByteArray): CertificateFingerprints = CertificateFingerprints(
            sha1 = hex(digest("SHA-1", encoded)).chunked(8).joinToString(" "),
            sha256 = hex(digest("SHA-256", encoded)).chunked(2).joinToString(" "),
        )

        private fun digest(algorithm: String, bytes: ByteArray): ByteArray =
            MessageDigest.getInstance(algorithm).digest(bytes)

        private fun hex(bytes: ByteArray): String =
            bytes.joinToString("") { "%02X".format(it.toInt() and 0xFF) }
    }
}
