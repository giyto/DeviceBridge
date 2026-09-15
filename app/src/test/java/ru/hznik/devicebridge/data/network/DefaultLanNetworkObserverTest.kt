package ru.hznik.devicebridge.data.network

import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultLanNetworkObserverTest {

    @Test
    fun observerRegistersOnceUnregistersAndIgnoresLateCallbacks() {
        val registrar = FakeNetworkCallbackRegistrar()
        val observer = DefaultLanNetworkObserver(registrar)
        val events = mutableListOf<LanNetworkEvent>()

        observer.start(events::add)
        observer.start(events::add)
        registrar.callback!!.onLinkPropertiesChanged(
            interfaceName = "wlan0",
            addresses = setOf("192.168.1.24"),
        )
        observer.stop()
        registrar.callback!!.onLost()

        assertEquals(1, registrar.registerCalls)
        assertEquals(1, registrar.unregisterCalls)
        assertEquals(
            listOf(
                LanNetworkEvent.LinkPropertiesChanged(
                    interfaceName = "wlan0",
                    addresses = setOf("192.168.1.24"),
                ),
            ),
            events,
        )
    }

    @Test
    fun androidCallbackAdapterUsesCallbackArgumentsOnly() {
        val source = Files.readString(
            Path.of(
                "src/main/java/ru/hznik/devicebridge/data/network/" +
                    "AndroidNetworkCallbackRegistrar.kt",
            ),
        )

        assertTrue(source.contains("onCapabilitiesChanged"))
        assertTrue(source.contains("onLinkPropertiesChanged"))
        assertTrue(source.contains("onLost"))
        assertFalse(source.contains("getNetworkCapabilities("))
        assertFalse(source.contains("getLinkProperties("))
    }

    private class FakeNetworkCallbackRegistrar : NetworkCallbackRegistrar {
        var callback: LanNetworkCallback? = null
        var registerCalls = 0
        var unregisterCalls = 0

        override fun register(callback: LanNetworkCallback) {
            registerCalls += 1
            this.callback = callback
        }

        override fun unregister(callback: LanNetworkCallback) {
            unregisterCalls += 1
        }
    }
}
