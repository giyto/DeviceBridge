package ru.hznik.devicebridge.data.permission

import android.Manifest
import android.app.AppOpsManager
import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AndroidPermissionChangeRegistrar @Inject constructor(
    @ApplicationContext context: Context,
) : PermissionChangeRegistrar {

    private val appContext = context.applicationContext
    private val appOpsManager =
        appContext.getSystemService(AppOpsManager::class.java)
    private val permissionOp =
        AppOpsManager.permissionToOp(Manifest.permission.ACCESS_LOCAL_NETWORK)
    override val appUid: Int = context.applicationInfo.uid
    private var platformListener: AppOpsManager.OnOpChangedListener? = null

    @Synchronized
    override fun register(listener: (Int) -> Unit) {
        if (platformListener != null) {
            return
        }
        val op = permissionOp ?: return
        val adapter = AppOpsManager.OnOpChangedListener { _, packageName ->
            if (packageName == appContext.packageName) {
                listener(appUid)
            }
        }
        platformListener = adapter
        appOpsManager.startWatchingMode(op, appContext.packageName, adapter)
    }

    @Synchronized
    override fun unregister() {
        val listener = platformListener ?: return
        appOpsManager.stopWatchingMode(listener)
        platformListener = null
    }
}
