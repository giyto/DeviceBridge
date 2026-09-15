package ru.hznik.devicebridge.data.permission

import org.junit.Assert.assertEquals
import org.junit.Test

class LocalNetworkPermissionObserverTest {

    @Test
    fun observerFiltersUidReportsRevocationOnceAndUnregisters() {
        val registrar = FakePermissionChangeRegistrar(appUid = 42)
        var granted = true
        val observer = LocalNetworkPermissionObserver(
            registrar = registrar,
            isPermissionRequired = { true },
            isLocalNetworkGranted = { granted },
        )
        var revocations = 0
        observer.start { revocations += 1 }

        registrar.dispatch(uid = 7)
        granted = false
        registrar.dispatch(uid = 42)
        registrar.dispatch(uid = 42)
        observer.stop()

        assertEquals(1, revocations)
        assertEquals(1, registrar.registerCalls)
        assertEquals(1, registrar.unregisterCalls)
    }

    @Test
    fun securityExceptionFailsClosed() {
        val registrar = FakePermissionChangeRegistrar(appUid = 42)
        val observer = LocalNetworkPermissionObserver(
            registrar = registrar,
            isPermissionRequired = { true },
            isLocalNetworkGranted = { throw SecurityException("revoked") },
        )
        var revocations = 0
        observer.start { revocations += 1 }

        registrar.dispatch(uid = 42)

        assertEquals(1, revocations)
    }

    private class FakePermissionChangeRegistrar(
        override val appUid: Int,
    ) : PermissionChangeRegistrar {
        private var listener: ((Int) -> Unit)? = null
        var registerCalls = 0
        var unregisterCalls = 0

        override fun register(listener: (Int) -> Unit) {
            registerCalls += 1
            this.listener = listener
        }

        override fun unregister() {
            unregisterCalls += 1
            listener = null
        }

        fun dispatch(uid: Int) {
            listener?.invoke(uid)
        }
    }
}
