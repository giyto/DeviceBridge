package ru.hznik.devicebridge.server

import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerManifestContractTest {

    private val manifest by lazy {
        Files.readString(Path.of("src/main/AndroidManifest.xml"))
    }

    @Test
    fun productionManifestDeclaresServerPermissions() {
        val permissions = listOf(
            "android.permission.INTERNET",
            "android.permission.ACCESS_NETWORK_STATE",
            "android.permission.ACCESS_WIFI_STATE",
            "android.permission.ACCESS_LOCAL_NETWORK",
            "android.permission.POST_NOTIFICATIONS",
            "android.permission.FOREGROUND_SERVICE",
            "android.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE",
            "android.permission.CHANGE_NETWORK_STATE",
            // The multicast lock that lets the phone hear questions about its local name.
            "android.permission.CHANGE_WIFI_MULTICAST_STATE",
        )

        permissions.forEach { permission ->
            assertTrue("Missing permission $permission", manifest.contains(permission))
        }
    }

    @Test
    fun serverServiceIsPrivateConnectedDeviceWithoutBootReceiver() {
        assertTrue(manifest.contains("android:name=\".server.ServerForegroundService\""))
        assertTrue(manifest.contains("android:exported=\"false\""))
        assertTrue(manifest.contains("android:foregroundServiceType=\"connectedDevice\""))
        assertFalse(manifest.contains("android.intent.action.BOOT_COMPLETED"))
    }
}
