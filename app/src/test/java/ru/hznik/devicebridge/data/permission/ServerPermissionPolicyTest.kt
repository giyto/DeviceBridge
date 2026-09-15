package ru.hznik.devicebridge.data.permission

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerPermissionPolicyTest {

    private val policy = ServerPermissionPolicy()

    @Test
    fun requirementsFollowPlatformBoundaries() {
        assertEquals(
            ServerPermissionRequirements(
                requiresLocalNetwork = false,
                requiresNotifications = false,
            ),
            policy.requirements(sdkInt = 29),
        )
        assertEquals(
            ServerPermissionRequirements(
                requiresLocalNetwork = false,
                requiresNotifications = true,
            ),
            policy.requirements(sdkInt = 33),
        )
        assertEquals(
            ServerPermissionRequirements(
                requiresLocalNetwork = true,
                requiresNotifications = true,
            ),
            policy.requirements(sdkInt = 37),
        )
    }

    @Test
    fun localNetworkDenialBlocksOnlyApi37AndNewer() {
        assertTrue(
            policy.evaluate(
                sdkInt = 29,
                localNetworkGranted = false,
                notificationsGranted = true,
            ).canStart,
        )
        assertFalse(
            policy.evaluate(
                sdkInt = 37,
                localNetworkGranted = false,
                notificationsGranted = true,
            ).canStart,
        )
    }

    @Test
    fun notificationDenialWarnsButDoesNotBlockForegroundStart() {
        val result = policy.evaluate(
            sdkInt = 37,
            localNetworkGranted = true,
            notificationsGranted = false,
        )

        assertTrue(result.canStart)
        assertTrue(result.showNotificationWarning)
        assertFalse(result.requestLocalNetwork)
    }
}
