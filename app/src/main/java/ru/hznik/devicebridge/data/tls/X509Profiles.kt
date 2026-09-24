package ru.hznik.devicebridge.data.tls

import java.io.ByteArrayInputStream
import java.net.Inet4Address
import java.security.MessageDigest
import java.security.PublicKey
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.time.Instant

/** The only name, besides private IPv4 ranges, that certificates from this phone may carry. */
const val DEVICEBRIDGE_LOCAL_NAME = "devicebridge.local"

/** Private IPv4 ranges the root may sign for, as address and mask. */
internal val PERMITTED_IPV4_RANGES: List<Pair<ByteArray, ByteArray>> = listOf(
    bytes(10, 0, 0, 0) to bytes(255, 0, 0, 0),
    bytes(172, 16, 0, 0) to bytes(255, 240, 0, 0),
    bytes(192, 168, 0, 0) to bytes(255, 255, 0, 0),
)

/**
 * Builds the two certificates DeviceBridge issues: its name-constrained root and the server
 * certificate that root signs. [sign] receives the TBS bytes and returns an ECDSA-SHA256
 * signature made by the issuer's key.
 */
internal object X509Profiles {

    fun root(
        publicKey: PublicKey,
        serial: ByteArray,
        commonName: String,
        notBefore: Instant,
        notAfter: Instant,
        sign: (ByteArray) -> ByteArray,
    ): X509Certificate {
        val name = name(commonName)
        val extensions = listOf(
            extension(OID_BASIC_CONSTRAINTS, critical = true, Der.sequence(Der.boolean(true), Der.integer(0))),
            extension(OID_KEY_USAGE, critical = true, Der.namedBits(KEY_CERT_SIGN, CRL_SIGN)),
            extension(OID_NAME_CONSTRAINTS, critical = true, nameConstraints()),
            extension(OID_SUBJECT_KEY_ID, critical = false, Der.octetString(keyId(publicKey))),
        )
        return build(publicKey, serial, issuer = name, subject = name, notBefore, notAfter, extensions, sign)
    }

    fun server(
        publicKey: PublicKey,
        serial: ByteArray,
        issuer: X509Certificate,
        addresses: List<Inet4Address>,
        notBefore: Instant,
        notAfter: Instant,
        sign: (ByteArray) -> ByteArray,
        dnsNames: List<String> = listOf(DEVICEBRIDGE_LOCAL_NAME),
    ): X509Certificate {
        require(addresses.isNotEmpty()) { "A server certificate needs at least one address" }
        val alternativeNames = addresses.map { Der.implicitPrimitive(GENERAL_NAME_IP, it.address) } +
            dnsNames.map { Der.implicitPrimitive(GENERAL_NAME_DNS, it.toByteArray(Charsets.US_ASCII)) }
        val extensions = listOf(
            extension(OID_BASIC_CONSTRAINTS, critical = true, Der.sequence()),
            extension(OID_KEY_USAGE, critical = true, Der.namedBits(DIGITAL_SIGNATURE)),
            extension(OID_EXTENDED_KEY_USAGE, critical = false, Der.sequence(Der.oid(OID_SERVER_AUTH))),
            extension(OID_SUBJECT_ALT_NAME, critical = false, Der.sequence(alternativeNames)),
            extension(OID_SUBJECT_KEY_ID, critical = false, Der.octetString(keyId(publicKey))),
            extension(
                OID_AUTHORITY_KEY_ID,
                critical = false,
                Der.sequence(Der.implicitPrimitive(0, keyId(issuer.publicKey))),
            ),
        )
        return build(
            publicKey = publicKey,
            serial = serial,
            issuer = issuer.subjectX500Principal.encoded,
            subject = name(DEVICEBRIDGE_LOCAL_NAME),
            notBefore = notBefore,
            notAfter = notAfter,
            extensions = extensions,
            sign = sign,
        )
    }

    private fun build(
        publicKey: PublicKey,
        serial: ByteArray,
        issuer: ByteArray,
        subject: ByteArray,
        notBefore: Instant,
        notAfter: Instant,
        extensions: List<ByteArray>,
        sign: (ByteArray) -> ByteArray,
    ): X509Certificate {
        require(serial.isNotEmpty() && serial.size <= 20 && serial[0] > 0) { "Serial must be positive" }
        require(notBefore < notAfter)
        val algorithm = Der.sequence(Der.oid(OID_ECDSA_WITH_SHA256))
        val tbs = Der.sequence(
            Der.explicit(0, Der.integer(2)),
            Der.tlv(TAG_INTEGER, serial),
            algorithm,
            issuer,
            Der.sequence(Der.time(notBefore), Der.time(notAfter)),
            subject,
            publicKey.encoded,
            Der.explicit(3, Der.sequence(extensions)),
        )
        val certificate = Der.sequence(tbs, algorithm, Der.bitString(sign(tbs)))
        return CertificateFactory.getInstance("X.509")
            .generateCertificate(ByteArrayInputStream(certificate)) as X509Certificate
    }

    private fun name(commonName: String): ByteArray = Der.sequence(
        Der.set(Der.sequence(Der.oid(OID_ORGANIZATION), Der.utf8String("DeviceBridge"))),
        Der.set(Der.sequence(Der.oid(OID_COMMON_NAME), Der.utf8String(commonName))),
    )

    private fun nameConstraints(): ByteArray {
        val permitted = PERMITTED_IPV4_RANGES.map { (address, mask) ->
            Der.sequence(Der.implicitPrimitive(GENERAL_NAME_IP, address + mask))
        } + Der.sequence(
            Der.implicitPrimitive(GENERAL_NAME_DNS, DEVICEBRIDGE_LOCAL_NAME.toByteArray(Charsets.US_ASCII)),
        )
        return Der.sequence(Der.implicitConstructed(0, permitted))
    }

    private fun extension(oid: String, critical: Boolean, value: ByteArray): ByteArray =
        if (critical) {
            Der.sequence(Der.oid(oid), Der.boolean(true), Der.octetString(value))
        } else {
            Der.sequence(Der.oid(oid), Der.octetString(value))
        }

    /** RFC 7093 style key identifier: SHA-1 over the whole SubjectPublicKeyInfo. */
    fun keyId(publicKey: PublicKey): ByteArray =
        MessageDigest.getInstance("SHA-1").digest(publicKey.encoded)

    private const val TAG_INTEGER = 0x02
    private const val GENERAL_NAME_DNS = 2
    private const val GENERAL_NAME_IP = 7
    private const val DIGITAL_SIGNATURE = 0
    private const val KEY_CERT_SIGN = 5
    private const val CRL_SIGN = 6

    private const val OID_COMMON_NAME = "2.5.4.3"
    private const val OID_ORGANIZATION = "2.5.4.10"
    private const val OID_ECDSA_WITH_SHA256 = "1.2.840.10045.4.3.2"
    private const val OID_SUBJECT_KEY_ID = "2.5.29.14"
    private const val OID_KEY_USAGE = "2.5.29.15"
    private const val OID_SUBJECT_ALT_NAME = "2.5.29.17"
    private const val OID_BASIC_CONSTRAINTS = "2.5.29.19"
    private const val OID_NAME_CONSTRAINTS = "2.5.29.30"
    private const val OID_AUTHORITY_KEY_ID = "2.5.29.35"
    private const val OID_EXTENDED_KEY_USAGE = "2.5.29.37"
    private const val OID_SERVER_AUTH = "1.3.6.1.5.5.7.3.1"
}

private fun bytes(vararg values: Int): ByteArray = ByteArray(values.size) { values[it].toByte() }
