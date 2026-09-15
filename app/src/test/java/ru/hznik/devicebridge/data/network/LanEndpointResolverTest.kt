package ru.hznik.devicebridge.data.network

import org.junit.Assert.assertEquals
import org.junit.Test
import ru.hznik.devicebridge.domain.model.ServerLifecycleError

class LanEndpointResolverTest {

    private val resolver = LanEndpointResolver()

    @Test
    fun activeWifiAddressWinsOverInterfaceFallback() {
        val result = resolver.resolve(
            LanNetworkSnapshot(
                activeWifiAddresses = listOf(address("192.168.1.24", "wlan0")),
                interfaceAddresses = listOf(address("192.168.43.1", "ap0")),
            ),
        )

        assertEquals(
            LanEndpointResolution.Resolved(
                ServerEndpointCandidate(
                    host = "192.168.1.24",
                    networkFingerprint = "wlan0|192.168.1.24",
                ),
            ),
            result,
        )
    }

    @Test
    fun hotspotAndAvdPrivateAddressesAreValidFallbacks() {
        assertEquals(
            "192.168.43.1",
            resolver.resolve(
                LanNetworkSnapshot(
                    interfaceAddresses = listOf(address("192.168.43.1", "ap0")),
                ),
            ).resolvedHost(),
        )
        assertEquals(
            "10.0.2.15",
            resolver.resolve(
                LanNetworkSnapshot(
                    interfaceAddresses = listOf(address("10.0.2.15", "eth0")),
                ),
            ).resolvedHost(),
        )
    }

    @Test
    fun missingOrAmbiguousFallbackReturnsTypedError() {
        assertEquals(
            LanEndpointResolution.Failed(ServerLifecycleError.NoLanNetwork),
            resolver.resolve(LanNetworkSnapshot()),
        )
        assertEquals(
            LanEndpointResolution.Failed(ServerLifecycleError.AmbiguousLanNetwork),
            resolver.resolve(
                LanNetworkSnapshot(
                    interfaceAddresses = listOf(
                        address("192.168.1.24", "wlan0"),
                        address("192.168.43.1", "ap0"),
                    ),
                ),
            ),
        )
    }

    @Test
    fun unsafeNonIpv4AndVirtualCandidatesAreExcluded() {
        val unsafe = listOf(
            address("0.0.0.0", "wlan0"),
            address("127.0.0.1", "lo"),
            address("169.254.10.20", "wlan0"),
            address("224.0.0.1", "wlan0"),
            address("8.8.8.8", "wlan0"),
            address("fe80::1", "wlan0"),
            address("192.168.1.2", "tun0"),
            address("192.168.1.3", "wlan1", isUp = false),
        )

        assertEquals(
            LanEndpointResolution.Failed(ServerLifecycleError.NoLanNetwork),
            resolver.resolve(LanNetworkSnapshot(interfaceAddresses = unsafe)),
        )
    }

    private fun address(
        host: String,
        interfaceName: String,
        isUp: Boolean = true,
    ) = LanAddressCandidate(host, interfaceName, isUp)

    private fun LanEndpointResolution.resolvedHost(): String? =
        (this as? LanEndpointResolution.Resolved)?.candidate?.host
}
