package ru.hznik.devicebridge.data.tls

import java.io.ByteArrayInputStream
import java.net.Inet4Address
import java.security.MessageDigest
import java.security.PublicKey
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.time.Instant

/** The name roots from before custom network names were limited to. */
const val DEVICEBRIDGE_LOCAL_NAME = "devicebridge.local"

/**
 * The DNS zone a root may sign for besides private IPv4 ranges: every `<name>.local`. Such
 * names exist only on the local network, so the root cannot vouch for a real website.
 */
const val LOCAL_DNS_ZONE = "local"

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
        /** Tests build roots from before custom names with `devicebridge.local` here. */
        permittedDnsName: String = LOCAL_DNS_ZONE,
    ): X509Certificate {
        val name = name(commonName)
        val extensions = listOf(
            extension(OID_BASIC_CONSTRAINTS, critical = true, Der.sequence(Der.boolean(true), Der.integer(0))),
            extension(OID_KEY_USAGE, critical = true, Der.namedBits(KEY_CERT_SIGN, CRL_SIGN)),
            extension(OID_NAME_CONSTRAINTS, critical = true, nameConstraints(permittedDnsName)),
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
        dnsNames: List<String> = emptyList(),
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
            subject = name(dnsNames.firstOrNull() ?: addresses.first().hostAddress!!),
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

    private fun nameConstraints(permittedDnsName: String): ByteArray {
        val permitted = PERMITTED_IPV4_RANGES.map { (address, mask) ->
            Der.sequence(Der.implicitPrimitive(GENERAL_NAME_IP, address + mask))
        } + Der.sequence(
            Der.implicitPrimitive(GENERAL_NAME_DNS, permittedDnsName.toByteArray(Charsets.US_ASCII)),
        )
        return Der.sequence(Der.implicitConstructed(0, permitted))
    }

    /**
     * The DNS names a root's name constraints permit, e.g. `local` or, for roots made before
     * custom network names, `devicebridge.local`. Empty when the root has no such constraint.
     */
    fun permittedDnsNames(root: X509Certificate): List<String> {
        val wrapped = root.getExtensionValue(OID_NAME_CONSTRAINTS) ?: return emptyList()
        return runCatching {
            val constraints = DerReader(DerReader(wrapped).single(TAG_OCTET_STRING)).single(TAG_SEQUENCE)
            val subtrees = DerReader(constraints).children()
                .firstOrNull { it.first == TAG_PERMITTED_SUBTREES }?.second ?: return emptyList()
            DerReader(subtrees).children()
                .filter { it.first == TAG_SEQUENCE }
                .mapNotNull { (_, subtree) -> DerReader(subtree).children().firstOrNull() }
                .filter { (tag, _) -> tag == TAG_DNS_NAME }
                .map { (_, value) -> String(value, Charsets.US_ASCII).lowercase() }
        }.getOrDefault(emptyList())
    }

    /** RFC 5280 dNSName matching: the name itself or any name below it. */
    fun permitsDnsName(permitted: List<String>, name: String): Boolean {
        val candidate = name.lowercase().trimEnd('.')
        return permitted.any { base -> candidate == base || candidate.endsWith(".$base") }
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
    private const val TAG_OCTET_STRING = 0x04
    private const val TAG_SEQUENCE = 0x30
    private const val TAG_PERMITTED_SUBTREES = 0xA0
    private const val TAG_DNS_NAME = 0x82
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

/** Reads the few DER structures the name constraints need: tag and content, nothing more. */
private class DerReader(private val bytes: ByteArray) {
    fun single(expectedTag: Int): ByteArray {
        val all = children()
        require(all.size == 1 && all[0].first == expectedTag) { "Unexpected DER" }
        return all[0].second
    }

    fun children(): List<Pair<Int, ByteArray>> {
        val result = mutableListOf<Pair<Int, ByteArray>>()
        var position = 0
        while (position < bytes.size) {
            val tag = bytes[position].toInt() and 0xFF
            require(tag and 0x1F != 0x1F) { "High tag numbers are not used here" }
            require(position + 1 < bytes.size) { "Truncated DER" }
            var length = bytes[position + 1].toInt() and 0xFF
            position += 2
            if (length and 0x80 != 0) {
                val count = length and 0x7F
                require(count in 1..3 && position + count <= bytes.size) { "Bad DER length" }
                length = 0
                repeat(count) { length = length shl 8 or (bytes[position++].toInt() and 0xFF) }
            }
            require(position + length <= bytes.size) { "Truncated DER" }
            result += tag to bytes.copyOfRange(position, position + length)
            position += length
        }
        return result
    }
}
