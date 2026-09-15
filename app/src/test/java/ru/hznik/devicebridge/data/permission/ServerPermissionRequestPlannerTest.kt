package ru.hznik.devicebridge.data.permission

import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerPermissionRequestPlannerTest {

    private val planner = ServerPermissionRequestPlanner(ServerPermissionPolicy())

    @Test
    fun api29NeverRequestsLocalNetworkOrNotificationRuntimePermissions() {
        val plan = planner.plan(
            ServerPermissionSnapshot(
                sdkInt = 29,
                localNetworkGranted = false,
                notificationsGranted = false,
            ),
        )

        assertTrue(plan.permissions.isEmpty())
        assertTrue(plan.canStart)
    }

    @Test
    fun api37RequestsMissingPermissionsAndAllowsRetry() {
        val snapshot = ServerPermissionSnapshot(
            sdkInt = 37,
            localNetworkGranted = false,
            notificationsGranted = false,
        )

        val initial = planner.plan(snapshot)
        val retry = planner.plan(snapshot.copy(localNetworkCanAskAgain = true))

        assertEquals(
            setOf(
                "android.permission.ACCESS_LOCAL_NETWORK",
                "android.permission.POST_NOTIFICATIONS",
            ),
            initial.permissions.toSet(),
        )
        assertFalse(initial.canStart)
        assertEquals(initial, retry)
    }

    @Test
    fun permanentLocalDenialBlocksWithoutRepeatingSystemDialog() {
        val plan = planner.plan(
            ServerPermissionSnapshot(
                sdkInt = 37,
                localNetworkGranted = false,
                notificationsGranted = false,
                localNetworkCanAskAgain = false,
            ),
        )

        assertFalse(plan.canStart)
        assertTrue(plan.localNetworkBlockedPermanently)
        assertFalse(
            plan.permissions.contains("android.permission.ACCESS_LOCAL_NETWORK"),
        )
        assertTrue(plan.showNotificationWarning)
    }

    @Test
    fun composeLauncherUsesActivityResultApi() {
        val source = Files.readString(
            Path.of(
                "src/main/java/ru/hznik/devicebridge/feature/home/ServerPermissionLauncher.kt",
            ),
        )

        assertTrue(source.contains("rememberLauncherForActivityResult"))
        assertTrue(source.contains("RequestMultiplePermissions"))
    }
}
