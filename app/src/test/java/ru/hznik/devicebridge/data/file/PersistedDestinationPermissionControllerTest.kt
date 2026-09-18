package ru.hznik.devicebridge.data.file

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PersistedDestinationPermissionControllerTest {
    @Test
    fun storesOnlyAnAvailablePersistedTreePermission() {
        val permissions = FakeTreePermissions()
        val controller = PersistedDestinationPermissionController(permissions)

        val result = controller.approve("content://provider/tree/devicebridge")

        assertEquals(
            SettingsDestinationApproval.Approved(
                "content://provider/tree/devicebridge",
            ),
            result,
        )
        assertTrue(permissions.acquiredFlags != 0)
        assertTrue(permissions.released.isEmpty())
    }

    @Test
    fun cancellationAndUnavailablePermissionDoNotReplaceTheSetting() {
        val controller = PersistedDestinationPermissionController(
            FakeTreePermissions(canAcquire = false),
        )

        assertEquals(SettingsDestinationApproval.Cancelled, controller.approve(null))
        assertEquals(
            SettingsDestinationApproval.Unavailable,
            controller.approve("content://provider/tree/revoked"),
        )
    }

    @Test
    fun retainedLeaseChecksAvailabilityWithoutReleasingTheSavedGrant() {
        val permissions = FakeTreePermissions()
        val controller = PersistedDestinationPermissionController(permissions)

        val result = controller.openPersisted("content://provider/tree/devicebridge")
        val lease = (result as DestinationApproval.Approved).lease
        lease.release()

        assertTrue(permissions.released.isEmpty())
    }

    @Test
    fun retainedLeaseRejectsARevokedSavedGrant() {
        val controller = PersistedDestinationPermissionController(
            FakeTreePermissions(canAcquire = false),
        )

        assertEquals(
            DestinationApproval.Unavailable,
            controller.openPersisted("content://provider/tree/revoked"),
        )
    }

    private class FakeTreePermissions(
        private val canAcquire: Boolean = true,
    ) : DocumentTreePermissionGateway {
        var acquiredFlags = 0
        val released = mutableListOf<String>()

        override fun acquire(uri: String, grantFlags: Int): Boolean {
            acquiredFlags = grantFlags
            return canAcquire
        }

        override fun isAvailable(uri: String): Boolean = canAcquire

        override fun release(uri: String, grantFlags: Int) {
            released += uri
        }
    }
}
