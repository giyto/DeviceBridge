package ru.hznik.devicebridge.data.network

interface LanNetworkObserver {
    fun start(onEvent: (LanNetworkEvent) -> Unit)

    fun stop()
}

sealed interface LanNetworkEvent {
    data class CapabilitiesChanged(
        val isSuitableWifi: Boolean,
    ) : LanNetworkEvent

    data class LinkPropertiesChanged(
        val interfaceName: String?,
        val addresses: Set<String>,
    ) : LanNetworkEvent

    data object Lost : LanNetworkEvent
}

interface LanNetworkCallback {
    fun onCapabilitiesChanged(isSuitableWifi: Boolean)

    fun onLinkPropertiesChanged(
        interfaceName: String?,
        addresses: Set<String>,
    )

    fun onLost()
}

interface NetworkCallbackRegistrar {
    fun register(callback: LanNetworkCallback)

    fun unregister(callback: LanNetworkCallback)
}
