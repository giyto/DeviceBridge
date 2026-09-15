package ru.hznik.devicebridge.data.permission

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AndroidServerPermissionGateway @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : ServerPermissionGateway {

    override fun snapshot(
        localNetworkCanAskAgain: Boolean,
    ): ServerPermissionSnapshot =
        ServerPermissionSnapshot(
            sdkInt = Build.VERSION.SDK_INT,
            localNetworkGranted = isGranted(Manifest.permission.ACCESS_LOCAL_NETWORK),
            notificationsGranted = isGranted(Manifest.permission.POST_NOTIFICATIONS),
            localNetworkCanAskAgain = localNetworkCanAskAgain,
        )

    private fun isGranted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) ==
            PackageManager.PERMISSION_GRANTED
}
