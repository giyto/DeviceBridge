package ru.hznik.devicebridge.data.tls

import java.net.InetAddress
import java.net.Inet4Address
import java.security.cert.CertPathValidator
import java.security.cert.CertPathValidatorException
import java.security.cert.CertificateFactory
import java.security.cert.PKIXParameters
import java.security.cert.TrustAnchor
import java.security.cert.X509Certificate
import java.time.Duration
import java.time.Instant
import java.util.Date
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class X509ProfilesTest {

    private val keys = SoftwareTlsKeyStore()
    private val now = Instant.parse("2026-09-24T12:00:00Z")

    @Test
    fun rootIsAConstrainedCertificateAuthority() {
        val root = root()

        assertEquals(3, root.version)
        assertEquals(0, root.basicConstraints)
        assertEquals(
            setOf("2.5.29.19", "2.5.29.15", "2.5.29.30"),
            root.criticalExtensionOIDs,
        )
        val usage = root.keyUsage
        assertTrue(usage[5] && usage[6])
        assertFalse(usage[0])
        assertEquals("CN=Тестовый корень,O=DeviceBridge", root.subjectX500Principal.name)
        assertEquals(root.subjectX500Principal, root.issuerX500Principal)
        root.verify(keys.publicKey(CA)!!)
        assertEquals(Date.from(now), root.notBefore)
        assertEquals(Date.from(now.plus(Duration.ofDays(3650))), root.notAfter)
        assertArrayEquals(
            hex(
                "302fa02d" +
                    "300a87080a000000ff000000" +
                    "300a8708ac100000fff00000" +
                    "300a8708c0a80000ffff0000" +
                    "300782056c6f63616c",
            ),
            nameConstraintsOf(root),
        )
    }

    @Test
    fun serverCertificateIsForTheAddressAndLocalName() {
        val root = root()
        val server = server(root, "192.168.1.24")

        assertEquals(-1, server.basicConstraints)
        assertEquals(listOf("1.3.6.1.5.5.7.3.1"), server.extendedKeyUsage)
        assertTrue(server.keyUsage[0])
        assertEquals(
            setOf(listOf<Any>(7, "192.168.1.24"), listOf<Any>(2, "devicebridge.local")),
            server.subjectAlternativeNames.map { it.toList() }.toSet(),
        )
        assertEquals(root.subjectX500Principal, server.issuerX500Principal)
        server.verify(root.publicKey)
        assertEquals(Date.from(now.minus(Duration.ofHours(24))), server.notBefore)
        assertNull(server.getExtensionValue("2.5.29.30"))
    }

    @Test
    fun chainValidatesForEachPrivateRange() {
        val root = root()
        listOf("10.0.0.2", "172.16.5.9", "172.31.255.1", "192.168.1.24").forEach { address ->
            validate(root, server(root, address))
        }
    }

    @Test
    fun rootCannotVouchForPublicAddressesOrNames() {
        val root = root()

        assertThrows(CertPathValidatorException::class.java) {
            validate(root, server(root, "8.8.8.8"))
        }
        assertThrows(CertPathValidatorException::class.java) {
            validate(root, server(root, "172.32.0.1"))
        }
        assertThrows(CertPathValidatorException::class.java) {
            validate(root, server(root, "192.168.1.24", dnsNames = listOf("example.com")))
        }
    }

    @Test
    fun rootVouchesForAnyLocalName() {
        val root = root()

        listOf("devicebridge.local", "nikita.local", "devicebridge-2.local").forEach { name ->
            validate(root, server(root, "192.168.1.24", dnsNames = listOf(name)))
        }
        assertEquals(listOf("local"), X509Profiles.permittedDnsNames(root))
    }

    @Test
    fun rootCannotVouchForNamesOutsideTheLocalZone() {
        val root = root()

        listOf("local.example.com", "example.local.com", "localhost").forEach { name ->
            assertThrows(name, CertPathValidatorException::class.java) {
                validate(root, server(root, "192.168.1.24", dnsNames = listOf(name)))
            }
        }
    }

    @Test
    fun rootFromBeforeCustomNamesOnlyVouchesForDeviceBridgeLocal() {
        val legacy = root(permittedDnsName = DEVICEBRIDGE_LOCAL_NAME)

        validate(legacy, server(legacy, "192.168.1.24", dnsNames = listOf("devicebridge.local")))
        assertThrows(CertPathValidatorException::class.java) {
            validate(legacy, server(legacy, "192.168.1.24", dnsNames = listOf("nikita.local")))
        }
        assertEquals(listOf("devicebridge.local"), X509Profiles.permittedDnsNames(legacy))
    }

    @Test
    fun dnsNameMatchingFollowsRfc5280() {
        assertTrue(X509Profiles.permitsDnsName(listOf("local"), "Nikita.Local"))
        assertTrue(X509Profiles.permitsDnsName(listOf("devicebridge.local"), "devicebridge.local"))
        assertFalse(X509Profiles.permitsDnsName(listOf("devicebridge.local"), "devicebridge-2.local"))
        assertFalse(X509Profiles.permitsDnsName(listOf("local"), "notlocal"))
        assertFalse(X509Profiles.permitsDnsName(emptyList(), "nikita.local"))
    }

    private fun root(permittedDnsName: String = LOCAL_DNS_ZONE): X509Certificate {
        val publicKey = keys.generate(CA, TlsKeyPurpose.CERTIFICATE_AUTHORITY)
        return X509Profiles.root(
            publicKey = publicKey,
            serial = byteArrayOf(0x11, 0x22),
            commonName = "Тестовый корень",
            notBefore = now,
            notAfter = now.plus(Duration.ofDays(3650)),
            sign = { keys.signSha256WithEcdsa(CA, it) },
            permittedDnsName = permittedDnsName,
        )
    }

    private fun server(
        root: X509Certificate,
        address: String,
        dnsNames: List<String> = listOf(DEVICEBRIDGE_LOCAL_NAME),
    ): X509Certificate {
        val publicKey = keys.generate(LEAF, TlsKeyPurpose.SERVER)
        return X509Profiles.server(
            publicKey = publicKey,
            serial = byteArrayOf(0x33),
            issuer = root,
            addresses = listOf(InetAddress.getByName(address) as Inet4Address),
            notBefore = now.minus(Duration.ofHours(24)),
            notAfter = now.plus(Duration.ofDays(30)),
            sign = { keys.signSha256WithEcdsa(CA, it) },
            dnsNames = dnsNames,
        )
    }

    /**
     * Java rejects name constraints on a trust anchor, so the self-signed root also sits in the
     * path, where its constraints apply to the server certificate as a browser applies them.
     */
    private fun validate(root: X509Certificate, server: X509Certificate) {
        val parameters = PKIXParameters(setOf(TrustAnchor(root, null))).apply {
            isRevocationEnabled = false
            date = Date.from(now)
        }
        val path = CertificateFactory.getInstance("X.509").generateCertPath(listOf(server, root))
        CertPathValidator.getInstance("PKIX").validate(path, parameters)
    }

    private fun nameConstraintsOf(certificate: X509Certificate): ByteArray {
        val wrapped = certificate.getExtensionValue("2.5.29.30")
        // Strip the OCTET STRING header; the constraints are short enough for one length byte.
        assertEquals(0x04, wrapped[0].toInt())
        return wrapped.copyOfRange(2, wrapped.size)
    }

    private fun hex(value: String): ByteArray =
        value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private companion object {
        const val CA = "ca"
        const val LEAF = "leaf"
    }
}
