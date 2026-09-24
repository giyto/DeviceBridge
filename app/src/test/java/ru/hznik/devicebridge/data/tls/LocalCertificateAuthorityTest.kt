package ru.hznik.devicebridge.data.tls

import java.io.File
import java.net.Inet4Address
import java.net.InetAddress
import java.security.SignatureException
import java.time.Duration
import java.time.Instant
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LocalCertificateAuthorityTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val keys = SoftwareTlsKeyStore()
    private var now = Instant.parse("2026-09-24T12:00:00Z")
    private val directory: File by lazy { File(folder.root, "tls") }
    private val authority by lazy { LocalCertificateAuthority(keys, directory, { now }) }

    @Test
    fun softwareKeyStoreCreatesReadsAndDeletesKeys() {
        val publicKey = keys.generate("alias", TlsKeyPurpose.SERVER)

        assertEquals(publicKey, keys.publicKey("alias"))
        assertTrue(keys.privateKey("alias") != null)
        keys.delete("alias")
        assertNull(keys.publicKey("alias"))
        assertThrows(TlsMaterialException::class.java) { keys.signSha256WithEcdsa("alias", byteArrayOf(1)) }
    }

    @Test
    fun rootIsCreatedOnceAndKept() {
        assertNull(authority.rootOrNull())

        val root = authority.ensureRoot()

        assertEquals(root, authority.ensureRoot())
        assertEquals(root, LocalCertificateAuthority(keys, directory, { now }).rootOrNull())
        assertTrue(root.subjectX500Principal.name.startsWith("CN=DeviceBridge Local CA 2026-09-24 "))
        assertEquals(now.plus(Duration.ofDays(3_650)), root.notAfter.toInstant())
    }

    @Test
    fun serverCertificateIsKeptWhileItCoversTheAddress() {
        val first = authority.serverMaterial(ip("192.168.1.24"))
        val second = authority.serverMaterial(ip("192.168.1.24"))

        assertEquals(first.certificate, second.certificate)
        assertEquals(first.root, second.root)
        first.certificate.verify(first.root.publicKey)
        assertEquals(now.minus(Duration.ofHours(24)), first.certificate.notBefore.toInstant())
        assertEquals(now.plus(Duration.ofDays(30)), first.certificate.notAfter.toInstant())
    }

    @Test
    fun newAddressGetsANewServerCertificateFromTheSameRoot() {
        val first = authority.serverMaterial(ip("192.168.1.24"))

        val moved = authority.serverMaterial(ip("10.0.0.7"))

        assertNotEquals(first.certificate, moved.certificate)
        assertEquals(first.root, moved.root)
        assertEquals(
            listOf(listOf<Any>(7, "10.0.0.7"), listOf<Any>(2, "devicebridge.local")),
            moved.certificate.subjectAlternativeNames.map { it.toList() },
        )
    }

    @Test
    fun serverCertificateIsReissuedAWeekBeforeItExpires() {
        val first = authority.serverMaterial(ip("192.168.1.24"))

        now = now.plus(Duration.ofDays(22))
        assertEquals(first.certificate, authority.serverMaterial(ip("192.168.1.24")).certificate)
        now = now.plus(Duration.ofDays(2))
        val renewed = authority.serverMaterial(ip("192.168.1.24")).certificate

        assertNotEquals(first.certificate, renewed)
        assertEquals(now.plus(Duration.ofDays(30)), renewed.notAfter.toInstant())
    }

    @Test
    fun damagedRootIsAnErrorRatherThanANewIdentity() {
        authority.ensureRoot()
        File(directory, "root.der").writeBytes(byteArrayOf(0x30, 0x03, 0x01))

        assertThrows(TlsMaterialException::class.java) { authority.rootOrNull() }
        assertThrows(TlsMaterialException::class.java) { authority.serverMaterial(ip("192.168.1.24")) }
    }

    @Test
    fun rootWithoutItsKeyIsAnError() {
        authority.ensureRoot()
        keys.delete(LocalCertificateAuthority.ROOT_ALIAS)

        assertThrows(TlsMaterialException::class.java) { authority.serverMaterial(ip("192.168.1.24")) }
    }

    @Test
    fun damagedServerCertificateIsReissued() {
        val first = authority.serverMaterial(ip("192.168.1.24"))
        File(directory, "server.der").writeBytes(byteArrayOf(1, 2, 3))

        val reissued = authority.serverMaterial(ip("192.168.1.24"))

        assertNotEquals(first.certificate, reissued.certificate)
        reissued.certificate.verify(first.root.publicKey)
    }

    @Test
    fun resetReplacesTheRootSoTheOldOneNoLongerVouches() {
        val before = authority.serverMaterial(ip("192.168.1.24"))

        val newRoot = authority.reset()
        val after = authority.serverMaterial(ip("192.168.1.24"))

        assertFalse(newRoot.publicKey.encoded.contentEquals(before.root.publicKey.encoded))
        assertArrayEquals(newRoot.encoded, after.root.encoded)
        assertThrows(SignatureException::class.java) { after.certificate.verify(before.root.publicKey) }
        after.certificate.verify(newRoot.publicKey)
    }

    private fun ip(value: String) = InetAddress.getByName(value) as Inet4Address
}
