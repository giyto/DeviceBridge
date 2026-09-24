package ru.hznik.devicebridge.data.tls

import java.io.ByteArrayInputStream
import java.io.File
import java.net.Inet4Address
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** What the TLS listener needs: the server certificate, the root that signed it, and its key. */
class ServerTlsMaterial(
    val certificate: X509Certificate,
    val root: X509Certificate,
    val privateKey: PrivateKey,
)

/**
 * The phone's own certificate authority. The root is created once and kept until a reset; the
 * server certificate is reissued whenever it no longer covers the published address or is
 * about to expire, which needs nothing from the computer that trusts the root.
 *
 * Certificates live as DER files in [directory], which must be excluded from backups; their
 * keys live in [keyStore] and cannot be read back.
 */
class LocalCertificateAuthority(
    private val keyStore: TlsKeyStore,
    private val directory: File,
    private val now: () -> Instant = Instant::now,
    private val random: SecureRandom = SecureRandom(),
    /** Key names in [keyStore]; tests on a device use their own so the app's keys stay intact. */
    private val rootAlias: String = ROOT_ALIAS,
    private val serverAlias: String = SERVER_ALIAS,
) {
    private val rootFile get() = File(directory, ROOT_FILE)
    private val serverFile get() = File(directory, SERVER_FILE)

    /** The installed root, or `null` when secure mode has never been set up. */
    @Synchronized
    fun rootOrNull(): X509Certificate? = if (rootFile.exists()) loadRoot() else null

    /** The root, created on first use. */
    @Synchronized
    fun ensureRoot(): X509Certificate = rootOrNull() ?: createRoot()

    @Synchronized
    fun serverMaterial(address: Inet4Address): ServerTlsMaterial {
        val root = ensureRoot()
        val current = loadServerOrNull(root)
        val certificate = if (current != null && covers(current, address) && !expiresSoon(current)) {
            current
        } else {
            issueServer(root, address)
        }
        val privateKey = keyStore.privateKey(serverAlias)
            ?: throw TlsMaterialException("Server key is missing")
        return ServerTlsMaterial(certificate, root, privateKey)
    }

    /** Forgets the root, the server certificate and both keys, then creates a new root. */
    @Synchronized
    fun reset(): X509Certificate {
        keyStore.delete(serverAlias)
        keyStore.delete(rootAlias)
        serverFile.delete()
        rootFile.delete()
        return createRoot()
    }

    private fun createRoot(): X509Certificate = wrapFailures("create the root") {
        keyStore.delete(serverAlias)
        serverFile.delete()
        val publicKey = keyStore.generate(rootAlias, TlsKeyPurpose.CERTIFICATE_AUTHORITY)
        val issuedAt = now()
        val keyTag = CertificateFingerprints.of(publicKey.encoded).sha1.take(4)
        val root = X509Profiles.root(
            publicKey = publicKey,
            serial = serial(),
            commonName = "DeviceBridge Local CA ${DATE.format(issuedAt)} $keyTag",
            notBefore = issuedAt,
            notAfter = issuedAt.plus(ROOT_VALIDITY),
            sign = { keyStore.signSha256WithEcdsa(rootAlias, it) },
        )
        writeAtomically(rootFile, root.encoded)
        root
    }

    private fun issueServer(root: X509Certificate, address: Inet4Address): X509Certificate =
        wrapFailures("issue the server certificate") {
            val publicKey = keyStore.generate(serverAlias, TlsKeyPurpose.SERVER)
            val issuedAt = now()
            val certificate = X509Profiles.server(
                publicKey = publicKey,
                serial = serial(),
                issuer = root,
                addresses = listOf(address),
                notBefore = issuedAt.minus(CLOCK_SKEW_ALLOWANCE),
                notAfter = issuedAt.plus(SERVER_VALIDITY),
                sign = { keyStore.signSha256WithEcdsa(rootAlias, it) },
            )
            writeAtomically(serverFile, certificate.encoded)
            certificate
        }

    private fun loadRoot(): X509Certificate = wrapFailures("read the root") {
        val root = parse(rootFile.readBytes())
        val key = keyStore.publicKey(rootAlias)
            ?: throw TlsMaterialException("The root key is missing")
        if (!key.encoded.contentEquals(root.publicKey.encoded)) {
            throw TlsMaterialException("The root does not match its key")
        }
        root.verify(key)
        root
    }

    /** A damaged or foreign server certificate is simply reissued. */
    private fun loadServerOrNull(root: X509Certificate): X509Certificate? {
        val key = keyStore.publicKey(serverAlias)
        if (!serverFile.exists() || key == null) return null
        return runCatching {
            parse(serverFile.readBytes()).also { certificate ->
                certificate.verify(root.publicKey)
                check(certificate.publicKey.encoded.contentEquals(key.encoded))
            }
        }.getOrNull()
    }

    private fun covers(certificate: X509Certificate, address: Inet4Address): Boolean =
        certificate.subjectAlternativeNames.orEmpty().any { name ->
            name[0] == GENERAL_NAME_IP && name[1] == address.hostAddress
        }

    private fun expiresSoon(certificate: X509Certificate): Boolean =
        certificate.notAfter.toInstant() < now().plus(REISSUE_BEFORE_EXPIRY)

    private fun serial(): ByteArray = ByteArray(SERIAL_BYTES).also { bytes ->
        random.nextBytes(bytes)
        // Positive and without a leading zero byte, as RFC 5280 asks of serial numbers.
        bytes[0] = ((bytes[0].toInt() and 0x7F) or 0x40).toByte()
    }

    private fun parse(bytes: ByteArray): X509Certificate =
        CertificateFactory.getInstance("X.509")
            .generateCertificate(ByteArrayInputStream(bytes)) as X509Certificate

    private fun writeAtomically(file: File, bytes: ByteArray) {
        directory.mkdirs()
        val temporary = File(directory, "${file.name}.tmp")
        temporary.writeBytes(bytes)
        if (!temporary.renameTo(file)) {
            file.delete()
            if (!temporary.renameTo(file)) throw TlsMaterialException("Cannot write ${file.name}")
        }
    }

    private inline fun <T> wrapFailures(action: String, block: () -> T): T = try {
        block()
    } catch (failure: TlsMaterialException) {
        throw failure
    } catch (failure: Exception) {
        throw TlsMaterialException("Cannot $action", failure)
    }

    companion object {
        const val ROOT_ALIAS = "devicebridge_tls_ca"
        const val SERVER_ALIAS = "devicebridge_tls_leaf"
        private const val ROOT_FILE = "root.der"
        private const val SERVER_FILE = "server.der"
        private const val SERIAL_BYTES = 16
        private const val GENERAL_NAME_IP = 7

        val ROOT_VALIDITY: Duration = Duration.ofDays(3_650)
        val SERVER_VALIDITY: Duration = Duration.ofDays(30)
        val REISSUE_BEFORE_EXPIRY: Duration = Duration.ofDays(7)
        val CLOCK_SKEW_ALLOWANCE: Duration = Duration.ofHours(24)

        private val DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneOffset.UTC)
    }
}
