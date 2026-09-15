package ru.hznik.devicebridge.server

import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ServerManifestInstrumentedTest {

    @Suppress("DEPRECATION")
    @Test
    fun installedPackageHasPrivateConnectedDeviceServiceAndPermissions() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val packageInfo = context.packageManager.getPackageInfo(
            context.packageName,
            PackageManager.GET_PERMISSIONS or
                PackageManager.GET_SERVICES,
        )
        val permissions = packageInfo.requestedPermissions.orEmpty().toSet()

        assertTrue(permissions.contains("android.permission.ACCESS_LOCAL_NETWORK"))
        assertTrue(permissions.contains("android.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE"))
        assertTrue(permissions.contains("android.permission.CHANGE_NETWORK_STATE"))

        val service = packageInfo.services
            .orEmpty()
            .singleOrNull { it.name == ServerForegroundService::class.java.name }
        assertNotNull(service)
        assertFalse(service!!.exported)
        assertEquals(
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
            service.foregroundServiceType and
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
        )
        val bootReceivers = context.packageManager.queryBroadcastReceivers(
            Intent(Intent.ACTION_BOOT_COMPLETED).setPackage(context.packageName),
            PackageManager.MATCH_DISABLED_COMPONENTS,
        )
        assertTrue(bootReceivers.isEmpty())
    }
}
