package ru.hznik.devicebridge.data.tls

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.io.IOException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.KeyStore
import java.security.cert.CertPathValidator
import java.security.cert.CertificateFactory
import java.security.cert.PKIXParameters
import java.security.cert.TrustAnchor
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManagerFactory
import kotlin.random.Random
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The secure-mode building blocks on a real Android runtime: keys in AndroidKeyStore signing
 * certificates, Conscrypt parsing them and serving TLS from a key it cannot read, and TLS and
 * plain HTTP sharing one port.
 */
@RunWith(AndroidJUnit4::class)
class TlsFrontDoorDeviceTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val directory = File(context.cacheDir, "tls-device-test")
    private val keys = AndroidTlsKeyStore()
    private val registry = RelayedConnectionRegistry()
    private lateinit var authority: LocalCertificateAuthority
    private lateinit var backend: ServerSocket
    private lateinit var door: TlsFrontDoor
    private val clients = mutableListOf<Socket>()

    @Before
    fun setUp() {
        directory.deleteRecursively()
        authority = LocalCertificateAuthority(
            keyStore = keys,
            directory = directory,
            rootAlias = TEST_ROOT_ALIAS,
            serverAlias = TEST_SERVER_ALIAS,
        )
        backend = ServerSocket(0, 50, TlsFrontDoor.BACKEND_HOST)
        Thread {
            while (!backend.isClosed) {
                val connection = try {
                    backend.accept()
                } catch (_: IOException) {
                    break
                }
                Thread { echo(connection) }.apply { isDaemon = true }.start()
            }
        }.apply { isDaemon = true }.start()
    }

    @After
    fun tearDown() {
        clients.forEach { runCatching { it.close() } }
        if (::door.isInitialized) door.close()
        backend.close()
        // Only this test's keys: it runs in the app's process, next to the app's real root.
        keys.delete(TEST_SERVER_ALIAS)
        keys.delete(TEST_ROOT_ALIAS)
        directory.deleteRecursively()
    }

    @Test
    fun keystoreKeysSignCertificatesThatAndroidAccepts() {
        val material = authority.serverMaterial(address("192.168.1.24"))

        // The keys stay inside AndroidKeyStore.
        assertNull(material.privateKey.encoded)
        assertNull(keys.privateKey(TEST_ROOT_ALIAS)!!.encoded)
        material.certificate.verify(material.root.publicKey)
        val parameters = PKIXParameters(setOf(TrustAnchor(material.root, null))).apply {
            isRevocationEnabled = false
        }
        CertPathValidator.getInstance("PKIX").validate(
            CertificateFactory.getInstance("X.509").generateCertPath(listOf(material.certificate)),
            parameters,
        )
    }

    @Test
    fun tlsAndPlainHttpShareOnePortWithAKeystoreKey() {
        val port = startDoor()

        val tls = tlsClient(port)
        assertArrayEquals("hello".toByteArray(), roundTrip(tls, "hello".toByteArray()))
        assertTrue(tls.session.protocol, tls.session.protocol in setOf("TLSv1.3", "TLSv1.2"))
        val plain = Socket(InetAddress.getLoopbackAddress(), port).also { clients += it }
        plain.soTimeout = 10_000
        assertArrayEquals("GET / HTTP/1.1\r\n".toByteArray(), roundTrip(plain, "GET / HTTP/1.1\r\n".toByteArray()))
        assertEquals(listOf(true, false), seenSecure)
    }

    @Test
    fun tls12ClientsAreServedToo() {
        val port = startDoor()

        val tls = tlsClient(port, arrayOf("TLSv1.2"))

        assertEquals("TLSv1.2", tls.session.protocol)
        assertArrayEquals("ok".toByteArray(), roundTrip(tls, "ok".toByteArray()))
    }

    @Test
    fun largeTransferSurvivesTls() {
        val port = startDoor()
        val tls = tlsClient(port)
        val payload = ByteArray(8 * 1024 * 1024).also { Random(11).nextBytes(it) }

        assertArrayEquals(payload, roundTrip(tls, payload))
    }

    private val seenSecure = java.util.concurrent.CopyOnWriteArrayList<Boolean>()

    private fun startDoor(): Int {
        val material = authority.serverMaterial(address("192.168.1.24"))
        door = TlsFrontDoor(TlsFrontDoor.serverContext(material), backend.localPort, registry)
        return door.start(InetAddress.getLoopbackAddress(), 0)
    }

    private fun tlsClient(port: Int, protocols: Array<String>? = null): SSLSocket {
        val trust = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null)
            setCertificateEntry("root", authority.ensureRoot())
        }
        val context = SSLContext.getInstance("TLS").apply {
            init(null, TrustManagerFactory.getInstance("PKIX").apply { init(trust) }.trustManagers, null)
        }
        val socket = context.socketFactory.createSocket(InetAddress.getLoopbackAddress(), port) as SSLSocket
        clients += socket
        protocols?.let { socket.enabledProtocols = it }
        socket.soTimeout = 20_000
        socket.startHandshake()
        return socket
    }

    private fun echo(connection: Socket) {
        connection.use {
            val buffer = ByteArray(16_384)
            var first = true
            while (true) {
                val count = try {
                    it.getInputStream().read(buffer)
                } catch (_: IOException) {
                    -1
                }
                if (count < 0) break
                if (first) {
                    registry.peerFor(it.port)?.let { peer -> seenSecure += peer.secure }
                    first = false
                }
                it.getOutputStream().write(buffer, 0, count)
            }
        }
    }

    private fun roundTrip(socket: Socket, bytes: ByteArray): ByteArray {
        val writer = Thread {
            socket.getOutputStream().apply {
                write(bytes)
                flush()
            }
        }.apply { start() }
        val received = ByteArray(bytes.size)
        var offset = 0
        while (offset < bytes.size) {
            val count = socket.getInputStream().read(received, offset, bytes.size - offset)
            check(count > 0) { "Connection ended early at $offset" }
            offset += count
        }
        writer.join()
        return received
    }

    private fun address(value: String) = InetAddress.getByName(value) as Inet4Address

    private companion object {
        const val TEST_ROOT_ALIAS = "devicebridge_tls_device_test_ca"
        const val TEST_SERVER_ALIAS = "devicebridge_tls_device_test_leaf"
    }
}
