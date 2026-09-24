package ru.hznik.devicebridge.data.tls

import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.io.PushbackInputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.security.Principal
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.KeyManager
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.X509ExtendedKeyManager

/** Who is on the other side of a connection that the front door relays to the local server. */
data class RelayedPeer(
    /** The address the browser connected from, as the socket reports it. */
    val address: String,
    /** Whether the browser reached the front door over TLS. */
    val secure: Boolean,
)

/**
 * Connections the front door currently relays, keyed by the local port of its socket to the
 * backend, which is the remote port the backend sees.
 */
class RelayedConnectionRegistry {
    private val peers = ConcurrentHashMap<Int, RelayedPeer>()

    fun peerFor(backendRemotePort: Int): RelayedPeer? = peers[backendRemotePort]

    internal fun register(backendRemotePort: Int, peer: RelayedPeer) {
        peers[backendRemotePort] = peer
    }

    internal fun remove(backendRemotePort: Int) {
        peers.remove(backendRemotePort)
    }

    internal fun size(): Int = peers.size
}

/**
 * Accepts browser connections on the published port and relays them to the local server on
 * loopback. A connection whose first byte starts a TLS record is decrypted here; anything else
 * is relayed as plain HTTP, so the server can answer it with the certificate setup page.
 * TLS runs on an [javax.net.ssl.SSLEngine] because Android offers no public way to layer an
 * SSLSocket over a socket whose first byte has already been read.
 */
class TlsFrontDoor(
    private val sslContext: SSLContext,
    private val backendPort: Int,
    private val registry: RelayedConnectionRegistry,
    private val maxConnections: Int = DEFAULT_MAX_CONNECTIONS,
    private val handshakeTimeoutMillis: Int = DEFAULT_HANDSHAKE_TIMEOUT_MILLIS,
) : Closeable {
    private val slots = Semaphore(maxConnections)
    private val open = ConcurrentHashMap.newKeySet<Socket>()
    private val threadNumber = AtomicInteger()
    private val executor: ExecutorService = Executors.newCachedThreadPool { task ->
        Thread(task, "DeviceBridge-TLS-${threadNumber.incrementAndGet()}").apply { isDaemon = true }
    }
    @Volatile private var serverSocket: ServerSocket? = null

    /** Binds [host]:[port] (0 picks a free port) and returns the port actually bound. */
    fun start(host: InetAddress, port: Int): Int {
        check(serverSocket == null) { "Front door is already started" }
        val socket = ServerSocket().apply {
            reuseAddress = true
            bind(InetSocketAddress(host, port))
        }
        serverSocket = socket
        executor.execute { acceptLoop(socket) }
        return socket.localPort
    }

    override fun close() {
        serverSocket?.closeQuietly()
        open.forEach { it.closeQuietly() }
        executor.shutdownNow()
    }

    private fun acceptLoop(server: ServerSocket) {
        while (!server.isClosed) {
            val client = try {
                server.accept()
            } catch (_: SocketException) {
                return
            }
            if (!slots.tryAcquire()) {
                client.closeQuietly()
                continue
            }
            open += client
            try {
                executor.execute { serve(client) }
            } catch (_: Exception) {
                release(client)
            }
        }
    }

    private fun serve(client: Socket) {
        var backend: Socket? = null
        try {
            client.soTimeout = handshakeTimeoutMillis
            val raw = PushbackInputStream(client.getInputStream(), 1)
            val first = raw.read()
            if (first < 0) return
            raw.unread(first)
            val secure = first == TLS_HANDSHAKE_RECORD
            val input: InputStream
            val output: OutputStream
            if (secure) {
                val engine = sslContext.createSSLEngine().apply {
                    useClientMode = false
                    enabledProtocols = serverProtocols(supportedProtocols)
                }
                val tls = TlsEngineStreams(engine, raw, client.getOutputStream())
                tls.handshake()
                input = tls.input
                output = tls.output
            } else {
                input = raw
                output = client.getOutputStream()
            }
            // Only the handshake is timed; transfers and live event sockets may idle for long.
            client.soTimeout = 0

            backend = Socket().apply {
                tcpNoDelay = true
                connect(InetSocketAddress(BACKEND_HOST, backendPort))
            }
            open += backend
            val backendPortKey = backend.localPort
            val peer = RelayedPeer(client.inetAddress?.hostAddress.orEmpty(), secure)
            registry.register(backendPortKey, peer)
            try {
                val connection = listOf(client, backend)
                val upstream = executor.submit { pump(input, backend.getOutputStream(), connection) }
                pump(backend.getInputStream(), output, connection)
                upstream.get()
            } finally {
                registry.remove(backendPortKey)
            }
        } catch (_: Exception) {
            // Failed handshakes, timeouts and resets end only this connection.
        } finally {
            backend?.let(::forget)
            release(client)
        }
    }

    /** Copies until either side ends, then closes the whole connection so the other side ends too. */
    private fun pump(from: InputStream, to: OutputStream, connection: List<Socket>) {
        val buffer = ByteArray(BUFFER_BYTES)
        try {
            while (true) {
                val count = from.read(buffer)
                if (count < 0) break
                to.write(buffer, 0, count)
                to.flush()
            }
        } catch (_: Exception) {
            // The connection is closed below either way.
        } finally {
            // Closing the stream first lets TLS say goodbye with close_notify.
            to.closeQuietly()
            connection.forEach { it.closeQuietly() }
        }
    }

    private fun forget(socket: Socket) {
        socket.closeQuietly()
        open -= socket
    }

    private fun release(client: Socket) {
        forget(client)
        slots.release()
    }

    companion object {
        const val DEFAULT_MAX_CONNECTIONS = 64
        const val DEFAULT_HANDSHAKE_TIMEOUT_MILLIS = 10_000
        private const val TLS_HANDSHAKE_RECORD = 0x16
        private const val BUFFER_BYTES = 64 * 1024
        private val ALLOWED_PROTOCOLS = listOf("TLSv1.3", "TLSv1.2")

        /**
         * Where the local server listens in secure mode. Explicitly IPv4: on Android the
         * platform's loopback address is `::1`, which the server does not listen on.
         */
        val BACKEND_HOST: InetAddress = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))

        /** TLS 1.2 and newer only, in order of preference. */
        fun serverProtocols(supported: Array<String>): Array<String> =
            ALLOWED_PROTOCOLS.filter { it in supported }.toTypedArray()

        /** A server context that presents [material]'s certificate from its non-exportable key. */
        fun serverContext(material: ServerTlsMaterial): SSLContext =
            SSLContext.getInstance("TLS").apply {
                init(arrayOf<KeyManager>(SingleIdentityKeyManager(material)), null, null)
            }
    }
}

/** Presents one EC server identity; it never acts as a client. */
private class SingleIdentityKeyManager(
    private val material: ServerTlsMaterial,
) : X509ExtendedKeyManager() {
    override fun chooseServerAlias(keyType: String?, issuers: Array<out Principal>?, socket: Socket?) =
        alias(keyType)

    override fun chooseEngineServerAlias(keyType: String?, issuers: Array<out Principal>?, engine: SSLEngine?) =
        alias(keyType)

    override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?) =
        alias(keyType)?.let { arrayOf(it) }

    override fun getCertificateChain(alias: String?): Array<X509Certificate>? =
        if (alias == ALIAS) arrayOf(material.certificate) else null

    override fun getPrivateKey(alias: String?): PrivateKey? =
        if (alias == ALIAS) material.privateKey else null

    override fun chooseClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, socket: Socket?) =
        null

    override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?) = null

    private fun alias(keyType: String?): String? =
        if (keyType == null || keyType.startsWith("EC")) ALIAS else null

    private companion object {
        const val ALIAS = "devicebridge"
    }
}

private fun Closeable.closeQuietly() {
    try {
        close()
    } catch (_: Exception) {
        // Already closed or broken; nothing left to release.
    }
}
