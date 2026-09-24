package ru.hznik.devicebridge.data.tls

import java.io.IOException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.KeyStore
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.random.Random
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManagerFactory
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TlsFrontDoorTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val registry = RelayedConnectionRegistry()
    private val seenPeers = CopyOnWriteArrayList<RelayedPeer?>()
    private lateinit var material: ServerTlsMaterial
    private lateinit var backend: ServerSocket
    private val doors = mutableListOf<TlsFrontDoor>()
    private val clients = mutableListOf<Socket>()

    @Before
    fun setUp() {
        material = LocalCertificateAuthority(SoftwareTlsKeyStore(), folder.newFolder("tls"))
            .serverMaterial(InetAddress.getByName("192.168.1.24") as Inet4Address)
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
        doors.forEach(TlsFrontDoor::close)
        backend.close()
    }

    @Test
    fun tlsAndPlainHttpShareOnePort() {
        val port = startDoor()

        val tls = tlsClient(port)
        assertArrayEquals("hello".toByteArray(), roundTrip(tls, "hello"))
        assertTrue(tls.session.protocol in setOf("TLSv1.3", "TLSv1.2"))

        val plain = plainClient(port)
        // The first byte decided the protocol and must still reach the server.
        assertArrayEquals("GET / HTTP/1.1\r\n".toByteArray(), roundTrip(plain, "GET / HTTP/1.1\r\n"))

        assertEquals(
            listOf(RelayedPeer("127.0.0.1", secure = true), RelayedPeer("127.0.0.1", secure = false)),
            seenPeers.toList(),
        )
        tls.close()
        plain.close()
        eventually { registry.size() == 0 }
    }

    @Test
    fun largeTransfersSurviveManyTlsRecordsInBothDirections() {
        val port = startDoor()
        val client = tlsClient(port)
        val payload = ByteArray(4 * 1024 * 1024).also { Random(7).nextBytes(it) }

        val writer = Thread {
            client.getOutputStream().apply {
                var offset = 0
                while (offset < payload.size) {
                    // Odd chunk sizes so records and reads never line up.
                    val count = minOf(37_123, payload.size - offset)
                    write(payload, offset, count)
                    offset += count
                }
                flush()
            }
        }.apply { start() }
        val received = ByteArray(payload.size)
        var offset = 0
        while (offset < received.size) {
            val count = client.getInputStream().read(received, offset, received.size - offset)
            check(count > 0) { "Connection ended early at $offset" }
            offset += count
        }
        writer.join()

        assertArrayEquals(payload, received)
    }

    @Test
    fun stalledHandshakeIsClosedWhileOtherConnectionsKeepWorking() {
        val port = startDoor(handshakeTimeoutMillis = 300)
        val working = tlsClient(port)
        roundTrip(working, "before")

        val stalled = plainSocket(port).apply { soTimeout = 3_000 }

        assertEquals(-1, stalled.getInputStream().read())
        // Longer than the handshake timeout: an established connection must not be cut.
        Thread.sleep(600)
        assertArrayEquals("after".toByteArray(), roundTrip(working, "after"))
    }

    @Test
    fun connectionsBeyondTheLimitAreClosedAtOnce() {
        val port = startDoor(maxConnections = 1)
        val first = tlsClient(port)
        roundTrip(first, "one")

        val second = plainSocket(port).apply { soTimeout = 3_000 }

        assertEquals(-1, second.getInputStream().read())
        assertArrayEquals("still".toByteArray(), roundTrip(first, "still"))
    }

    @Test
    fun closingTheDoorEndsConnectionsAndFreesThePort() {
        val door = TlsFrontDoor(TlsFrontDoor.serverContext(material), backend.localPort, registry)
        val port = door.start(InetAddress.getLoopbackAddress(), 0)
        val client = tlsClient(port)
        roundTrip(client, "open")

        door.close()

        client.soTimeout = 3_000
        val ended = try {
            client.inputStream.read() == -1
        } catch (_: IOException) {
            true
        }
        assertTrue(ended)
        eventually {
            runCatching {
                ServerSocket().use { it.bind(InetSocketAddress(InetAddress.getLoopbackAddress(), port)) }
            }.isSuccess
        }
    }

    @Test
    fun onlyTls12AndNewerAreOffered() {
        assertArrayEquals(
            arrayOf("TLSv1.3", "TLSv1.2"),
            TlsFrontDoor.serverProtocols(arrayOf("SSLv3", "TLSv1", "TLSv1.1", "TLSv1.2", "TLSv1.3")),
        )
        val port = startDoor()

        assertThrows(SSLException::class.java) {
            tlsClient(port, protocols = arrayOf("TLSv1.1"))
        }
        assertEquals("TLSv1.2", tlsClient(port, protocols = arrayOf("TLSv1.2")).session.protocol)
    }

    private fun startDoor(
        maxConnections: Int = TlsFrontDoor.DEFAULT_MAX_CONNECTIONS,
        handshakeTimeoutMillis: Int = TlsFrontDoor.DEFAULT_HANDSHAKE_TIMEOUT_MILLIS,
    ): Int {
        val door = TlsFrontDoor(
            sslContext = TlsFrontDoor.serverContext(material),
            backendPort = backend.localPort,
            registry = registry,
            maxConnections = maxConnections,
            handshakeTimeoutMillis = handshakeTimeoutMillis,
        )
        doors += door
        return door.start(InetAddress.getLoopbackAddress(), 0)
    }

    private fun echo(connection: Socket) {
        connection.use {
            val buffer = ByteArray(8_192)
            var first = true
            while (true) {
                val count = try {
                    it.getInputStream().read(buffer)
                } catch (_: IOException) {
                    -1
                }
                if (count < 0) break
                if (first) {
                    seenPeers += registry.peerFor(it.port)
                    first = false
                }
                it.getOutputStream().write(buffer, 0, count)
            }
        }
    }

    private fun tlsClient(port: Int, protocols: Array<String>? = null): SSLSocket {
        val trust = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null)
            setCertificateEntry("root", material.root)
        }
        val context = SSLContext.getInstance("TLS").apply {
            init(null, TrustManagerFactory.getInstance("PKIX").apply { init(trust) }.trustManagers, null)
        }
        val socket = context.socketFactory.createSocket(InetAddress.getLoopbackAddress(), port) as SSLSocket
        clients += socket
        protocols?.let { socket.enabledProtocols = it }
        socket.soTimeout = 5_000
        socket.startHandshake()
        return socket
    }

    private fun plainClient(port: Int): Socket = plainSocket(port).apply { soTimeout = 5_000 }

    private fun plainSocket(port: Int): Socket =
        Socket(InetAddress.getLoopbackAddress(), port).also { clients += it }

    private fun roundTrip(socket: Socket, text: String): ByteArray {
        val bytes = text.toByteArray()
        socket.getOutputStream().apply {
            write(bytes)
            flush()
        }
        val received = ByteArray(bytes.size)
        var offset = 0
        while (offset < bytes.size) {
            val count = socket.getInputStream().read(received, offset, bytes.size - offset)
            check(count > 0) { "Connection ended early" }
            offset += count
        }
        return received
    }

    private fun eventually(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "Condition not met in time" }
            Thread.sleep(20)
        }
    }
}
