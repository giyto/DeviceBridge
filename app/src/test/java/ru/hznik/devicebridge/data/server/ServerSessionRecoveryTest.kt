package ru.hznik.devicebridge.data.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import ru.hznik.devicebridge.data.permission.ServerPermissionGateway
import ru.hznik.devicebridge.data.permission.ServerPermissionSnapshot
import ru.hznik.devicebridge.domain.model.ServerLifecycleError
import ru.hznik.devicebridge.domain.model.ServerLifecycleState

class ServerSessionRecoveryTest {

    @Test
    fun interruptedApi37SessionWithRevokedLanPermissionBecomesRecoverableError() {
        val journal = FakeServerSessionJournal(wasRunning = true)
        val coordinator = coordinator(
            journal = journal,
            permissionSnapshot = ServerPermissionSnapshot(
                sdkInt = 37,
                localNetworkGranted = false,
                notificationsGranted = true,
            ),
        )

        assertEquals(
            ServerLifecycleState.Error(
                generation = 1,
                cause = ServerLifecycleError.PermissionRevoked,
            ),
            coordinator.state.value,
        )
        assertFalse(journal.wasRunning)
    }

    @Test
    fun interruptedSessionNeverRestartsWhenRequiredPermissionIsStillGranted() {
        val journal = FakeServerSessionJournal(wasRunning = true)
        val coordinator = coordinator(
            journal = journal,
            permissionSnapshot = ServerPermissionSnapshot(
                sdkInt = 37,
                localNetworkGranted = true,
                notificationsGranted = true,
            ),
        )

        assertEquals(ServerLifecycleState.Stopped, coordinator.state.value)
        assertFalse(journal.wasRunning)
    }

    @Test
    fun cleanLaunchRemainsStoppedEvenWhenPermissionIsDenied() {
        val coordinator = coordinator(
            journal = FakeServerSessionJournal(wasRunning = false),
            permissionSnapshot = ServerPermissionSnapshot(
                sdkInt = 37,
                localNetworkGranted = false,
                notificationsGranted = true,
            ),
        )

        assertEquals(ServerLifecycleState.Stopped, coordinator.state.value)
    }

    private fun coordinator(
        journal: ServerSessionJournal,
        permissionSnapshot: ServerPermissionSnapshot,
    ): ServerLifecycleCoordinator = ServerLifecycleCoordinator(
        runtimeFactory = ServerRuntimeFactory { error("Runtime must not start during recovery") },
        monotonicClock = MonotonicClock { 0 },
        sessionJournal = journal,
        permissionGateway = object : ServerPermissionGateway {
            override fun snapshot(localNetworkCanAskAgain: Boolean): ServerPermissionSnapshot =
                permissionSnapshot
        },
    )

    private class FakeServerSessionJournal(
        var wasRunning: Boolean,
    ) : ServerSessionJournal {
        override fun consumeInterruptedSession(): Boolean =
            wasRunning.also { wasRunning = false }

        override fun markRunning() {
            wasRunning = true
        }

        override fun clear() {
            wasRunning = false
        }
    }
}
