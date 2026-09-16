package ru.hznik.devicebridge.web

import org.junit.Assert.assertEquals
import org.junit.Test

class RemoteClientAddressTest {

    @Test
    fun mapsAdbLoopbackAliasesToCanonicalIpv4() {
        listOf("localhost", "::1", "0:0:0:0:0:0:0:1").forEach { address ->
            assertEquals("127.0.0.1", RemoteClientAddress.canonicalIpv4(address))
        }
    }

    @Test
    fun preservesLanIpv4AndLeavesUnsupportedAddressesForValidation() {
        assertEquals("192.168.0.90", RemoteClientAddress.canonicalIpv4("192.168.0.90"))
        assertEquals("device.local", RemoteClientAddress.canonicalIpv4("device.local"))
    }
}
