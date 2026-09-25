package ru.hznik.devicebridge.data.network.mdns

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.net.DatagramPacket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.SocketTimeoutException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The real socket on the emulator's Wi-Fi: a client on the same interface asks for the name
 * and gets the server's address, then hears the goodbye after close.
 */
@RunWith(AndroidJUnit4::class)
class LocalNamePublisherDeviceTest {

    private val group: InetAddress = InetAddress.getByName("224.0.0.251")

    @Test
    fun answersAQuestionAndSaysGoodbye() = runBlocking {
        val (networkInterface, address) = wifiAddress()
        val publisher = AndroidLocalNamePublisher(ApplicationProvider.getApplicationContext())
        val label = "dbtest-" + System.nanoTime().toString(36).takeLast(6)
        val session = requireNotNull(publisher.open(address.hostAddress!!, networkInterface.name) {}) {
            "mDNS socket did not open on ${networkInterface.name}"
        }
        val listener = MulticastSocket(null as InetSocketAddress?).apply {
            reuseAddress = true
            bind(InetSocketAddress(5353))
            this.networkInterface = networkInterface
            joinGroup(InetSocketAddress(group, 5353), networkInterface)
            soTimeout = 3_000
        }
        try {
            val claim = session.claim(label)
            assertEquals(LocalNameClaim.Claimed("$label.local", requestedTaken = false), claim)

            val answer = askLegacy(networkInterface, "$label.local")
            assertArrayEquals(address.address, answer.answers.single { it.type == MdnsType.A }.data)

            session.close()
            val goodbye = receiveUntil(listener) { message ->
                message.isResponse && message.answers.any { it.name == "$label.local" && it.ttlSeconds == 0L }
            }
            assertTrue(goodbye)
        } finally {
            session.close()
            listener.close()
        }
    }

    private fun askLegacy(networkInterface: NetworkInterface, name: String): MdnsMessage {
        MulticastSocket(0).use { client ->
            client.networkInterface = networkInterface
            client.soTimeout = 1_000
            val query = MdnsCodec.encode(
                MdnsMessage(id = 0x5151, isResponse = false, questions = listOf(MdnsQuestion(name, MdnsType.A))),
            )
            repeat(5) {
                client.send(DatagramPacket(query, query.size, InetSocketAddress(group, 5353)))
                val buffer = ByteArray(MdnsCodec.MAX_PACKET_BYTES)
                try {
                    while (true) {
                        val packet = DatagramPacket(buffer, buffer.size)
                        client.receive(packet)
                        val message = MdnsCodec.decode(buffer, packet.length) ?: continue
                        if (message.isResponse && message.id == 0x5151) return message
                    }
                } catch (_: SocketTimeoutException) {
                    // Ask again.
                }
            }
        }
        throw AssertionError("No answer for $name")
    }

    private fun receiveUntil(socket: MulticastSocket, predicate: (MdnsMessage) -> Boolean): Boolean {
        val buffer = ByteArray(MdnsCodec.MAX_PACKET_BYTES)
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            val packet = DatagramPacket(buffer, buffer.size)
            try {
                socket.receive(packet)
            } catch (_: SocketTimeoutException) {
                continue
            }
            val message = MdnsCodec.decode(buffer, packet.length) ?: continue
            if (predicate(message)) return true
        }
        return false
    }

    private fun wifiAddress(): Pair<NetworkInterface, Inet4Address> {
        val found = NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback && it.supportsMulticast() && it.name.startsWith("wlan") }
            .firstNotNullOfOrNull { candidate ->
                candidate.inetAddresses.toList().filterIsInstance<Inet4Address>().firstOrNull()?.let { candidate to it }
            }
        assumeTrue("The device needs a Wi-Fi interface with IPv4", found != null)
        return found!!
    }
}
