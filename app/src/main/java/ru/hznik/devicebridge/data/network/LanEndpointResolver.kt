package ru.hznik.devicebridge.data.network

import javax.inject.Inject
import ru.hznik.devicebridge.domain.model.ServerLifecycleError

class LanEndpointResolver @Inject constructor() {

    fun resolve(snapshot: LanNetworkSnapshot): LanEndpointResolution {
        val activeWifi = snapshot.activeWifiAddresses
            .filter(::isUsable)
            .distinct()
        if (activeWifi.isNotEmpty()) {
            return activeWifi.toResolution()
        }

        return snapshot.interfaceAddresses
            .filter(::isUsable)
            .distinct()
            .toResolution()
    }

    private fun List<LanAddressCandidate>.toResolution(): LanEndpointResolution =
        when (size) {
            0 -> LanEndpointResolution.Failed(
                ServerLifecycleError.NoLanNetwork,
            )

            1 -> first().let { address ->
                LanEndpointResolution.Resolved(
                    ServerEndpointCandidate(
                        host = address.host,
                        networkFingerprint =
                            address.interfaceName + "|" + address.host,
                    ),
                )
            }

            else -> LanEndpointResolution.Failed(
                ServerLifecycleError.AmbiguousLanNetwork,
            )
        }

    private fun isUsable(candidate: LanAddressCandidate): Boolean {
        if (!candidate.isUp || candidate.interfaceName.isVirtualInterface()) {
            return false
        }
        return candidate.host.isPrivateIpv4()
    }

    private fun String.isVirtualInterface(): Boolean {
        val normalized = lowercase()
        return normalized == "lo" ||
            normalized.startsWith("tun") ||
            normalized.startsWith("tap") ||
            normalized.startsWith("vpn") ||
            normalized.startsWith("wg") ||
            normalized.startsWith("veth") ||
            normalized.startsWith("docker")
    }

    private fun String.isPrivateIpv4(): Boolean {
        val octets = split('.').map { part ->
            part.toIntOrNull() ?: return false
        }
        if (octets.size != 4 || octets.any { it !in 0..255 }) {
            return false
        }
        val first = octets[0]
        val second = octets[1]
        return first == 10 ||
            (first == 172 && second in 16..31) ||
            (first == 192 && second == 168)
    }
}
