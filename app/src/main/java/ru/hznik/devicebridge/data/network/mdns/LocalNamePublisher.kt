package ru.hznik.devicebridge.data.network.mdns

import android.content.Context
import android.net.wifi.WifiManager
import android.os.SystemClock
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import java.net.DatagramPacket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import javax.inject.Inject

/** One running name: claim it, announce it, then close it on stop. */
interface LocalNameSession {
    val currentName: String?

    suspend fun claim(requestedLabel: String): LocalNameClaim

    suspend fun announce()

    /** Says goodbye for the name and frees the socket and the multicast lock. Idempotent. */
    fun close()
}

fun interface LocalNamePublisher {
    /**
     * Opens mDNS on the interface of the published endpoint. Returns null when this phone or
     * network does not let the app take part in mDNS: the server then works by IP only.
     */
    fun open(host: String, interfaceName: String, onNameLost: () -> Unit): LocalNameSession?

    companion object {
        val Disabled = LocalNamePublisher { _, _, _ -> null }
    }
}

class AndroidLocalNamePublisher @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : LocalNamePublisher {

    override fun open(host: String, interfaceName: String, onNameLost: () -> Unit): LocalNameSession? {
        val wifi = context.applicationContext.getSystemService(WifiManager::class.java)
        val lock = wifi?.createMulticastLock(LOCK_TAG)?.apply { setReferenceCounted(false) }
        return try {
            lock?.acquire()
            val address = InetAddress.getByName(host) as? Inet4Address ?: return null.also { lock?.release() }
            val networkInterface = NetworkInterface.getByName(interfaceName) ?: return null.also { lock?.release() }
            val prefix = networkInterface.interfaceAddresses
                .firstOrNull { it.address == address }
                ?.networkPrefixLength?.toInt()
                ?: DEFAULT_PREFIX
            val socket = MulticastMdnsSocket(networkInterface, address)
            val responder = LocalNameResponder(
                sender = socket,
                address = address.address,
                subnet = Ipv4Subnet(address.address, prefix),
                nowMs = SystemClock::elapsedRealtime,
                onNameLost = onNameLost,
            )
            socket.start(responder::onPacket)
            AndroidLocalNameSession(responder, socket) { runCatching { lock?.release() } }
        } catch (_: IOException) {
            runCatching { lock?.release() }
            null
        } catch (_: SecurityException) {
            runCatching { lock?.release() }
            null
        }
    }

    private class AndroidLocalNameSession(
        private val responder: LocalNameResponder,
        private val socket: MulticastMdnsSocket,
        private val releaseLock: () -> Unit,
    ) : LocalNameSession {
        @Volatile
        private var closed = false

        override val currentName: String?
            get() = responder.currentName

        override suspend fun claim(requestedLabel: String): LocalNameClaim = responder.claim(requestedLabel)

        override suspend fun announce() = responder.announce()

        override fun close() {
            if (closed) return
            closed = true
            runCatching { responder.release() }
            socket.close()
            releaseLock()
        }
    }

    private companion object {
        const val LOCK_TAG = "DeviceBridge:mdns"
        const val DEFAULT_PREFIX = 24
    }
}

/**
 * The mDNS socket on one interface. It shares port 5353 with the system's own mDNS service
 * (SO_REUSEADDR before bind) and reads packets on its own thread.
 */
internal class MulticastMdnsSocket(
    private val networkInterface: NetworkInterface,
    localAddress: Inet4Address,
) : MdnsSender {
    private val socket = MulticastSocket(null as InetSocketAddress?).also { socket ->
        try {
            socket.reuseAddress = true
            socket.bind(InetSocketAddress(LocalNameResponder.MDNS_PORT))
            socket.networkInterface = networkInterface
            socket.timeToLive = MULTICAST_TTL
            socket.joinGroup(InetSocketAddress(GROUP, LocalNameResponder.MDNS_PORT), networkInterface)
        } catch (failure: IOException) {
            socket.close()
            throw failure
        }
    }
    private val ownAddress = localAddress
    private var reader: Thread? = null

    @Volatile
    private var open = true

    fun start(onPacket: (ByteArray, Int, ByteArray, Int) -> Unit) {
        reader = Thread({
            val buffer = ByteArray(MdnsCodec.MAX_PACKET_BYTES)
            while (open) {
                val packet = DatagramPacket(buffer, buffer.size)
                try {
                    socket.receive(packet)
                } catch (_: IOException) {
                    if (!open) break else continue
                }
                val source = packet.address as? Inet4Address ?: continue
                if (source == ownAddress && packet.port == LocalNameResponder.MDNS_PORT) continue
                runCatching { onPacket(buffer, packet.length, source.address, packet.port) }
            }
        }, "DeviceBridge-mdns").apply {
            isDaemon = true
            start()
        }
    }

    override fun send(packet: ByteArray, destination: MdnsDestination) {
        if (!open) return
        try {
            val target = when (destination) {
                MdnsDestination.Multicast -> InetSocketAddress(GROUP, LocalNameResponder.MDNS_PORT)
                is MdnsDestination.Unicast ->
                    InetSocketAddress(InetAddress.getByAddress(destination.address), destination.port)
            }
            socket.send(DatagramPacket(packet, packet.size, target))
        } catch (_: IOException) {
            // A lost mDNS packet is harmless: askers retry and the name still works by IP.
        }
    }

    fun close() {
        if (!open) return
        open = false
        runCatching { socket.leaveGroup(InetSocketAddress(GROUP, LocalNameResponder.MDNS_PORT), networkInterface) }
        socket.close()
        reader?.join(READER_JOIN_MS)
    }

    private companion object {
        val GROUP: InetAddress = InetAddress.getByName("224.0.0.251")
        const val MULTICAST_TTL = 255
        const val READER_JOIN_MS = 1_000L
    }
}
