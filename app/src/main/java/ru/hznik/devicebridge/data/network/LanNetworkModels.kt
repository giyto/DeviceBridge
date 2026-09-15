package ru.hznik.devicebridge.data.network

import ru.hznik.devicebridge.domain.model.ServerLifecycleError

data class LanAddressCandidate(
    val host: String,
    val interfaceName: String,
    val isUp: Boolean,
)

data class LanNetworkSnapshot(
    val activeWifiAddresses: List<LanAddressCandidate> = emptyList(),
    val interfaceAddresses: List<LanAddressCandidate> = emptyList(),
)

data class ServerEndpointCandidate(
    val host: String,
    val networkFingerprint: String,
)

sealed interface LanEndpointResolution {
    data class Resolved(
        val candidate: ServerEndpointCandidate,
    ) : LanEndpointResolution

    data class Failed(
        val error: ServerLifecycleError,
    ) : LanEndpointResolution
}
