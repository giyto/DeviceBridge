package ru.hznik.devicebridge.data.network

import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DefaultLanNetworkObserver @Inject constructor(
    private val registrar: NetworkCallbackRegistrar,
) : LanNetworkObserver {

    private val lock = Any()
    private var activeCallback: LanNetworkCallback? = null

    override fun start(onEvent: (LanNetworkEvent) -> Unit) {
        val callback = synchronized(lock) {
            if (activeCallback != null) {
                return
            }

            object : LanNetworkCallback {
                override fun onCapabilitiesChanged(isSuitableWifi: Boolean) {
                    emitIfActive(
                        LanNetworkEvent.CapabilitiesChanged(isSuitableWifi),
                        onEvent,
                    )
                }

                override fun onLinkPropertiesChanged(
                    interfaceName: String?,
                    addresses: Set<String>,
                ) {
                    emitIfActive(
                        LanNetworkEvent.LinkPropertiesChanged(
                            interfaceName = interfaceName,
                            addresses = addresses,
                        ),
                        onEvent,
                    )
                }

                override fun onLost() {
                    emitIfActive(LanNetworkEvent.Lost, onEvent)
                }
            }.also { activeCallback = it }
        }

        try {
            registrar.register(callback)
        } catch (throwable: Throwable) {
            synchronized(lock) {
                if (activeCallback === callback) {
                    activeCallback = null
                }
            }
            throw throwable
        }
    }

    override fun stop() {
        val callback = synchronized(lock) {
            activeCallback.also { activeCallback = null }
        } ?: return

        runCatching { registrar.unregister(callback) }
    }

    private fun LanNetworkCallback.emitIfActive(
        event: LanNetworkEvent,
        onEvent: (LanNetworkEvent) -> Unit,
    ) {
        val shouldEmit = synchronized(lock) { activeCallback === this }
        if (shouldEmit) {
            onEvent(event)
        }
    }
}
