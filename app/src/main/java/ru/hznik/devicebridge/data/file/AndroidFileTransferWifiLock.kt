package ru.hznik.devicebridge.data.file

import android.content.Context
import android.net.wifi.WifiManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import ru.hznik.devicebridge.domain.file.FileTransferId

@Singleton
class AndroidFileTransferWifiLock @Inject constructor(
    @param:ApplicationContext context: Context,
) : FileTransferWifiLock {
    private val delegate = ReferenceCountedFileTransferWifiLock(
        AndroidWifiLockHandle(
            requireNotNull(
                context.applicationContext.getSystemService(Context.WIFI_SERVICE)
                    as? WifiManager,
            ).createWifiLock(
                WifiManager.WIFI_MODE_FULL_HIGH_PERF,
                "DeviceBridge:file-transfer",
            ).apply { setReferenceCounted(false) },
        ),
    )

    override fun acquire(transferId: FileTransferId) = delegate.acquire(transferId)
    override fun release(transferId: FileTransferId) = delegate.release(transferId)
    override fun releaseAll() = delegate.releaseAll()

    private class AndroidWifiLockHandle(
        private val lock: WifiManager.WifiLock,
    ) : PlatformWifiLockHandle {
        override val isHeld: Boolean
            get() = lock.isHeld

        override fun acquire() = lock.acquire()
        override fun release() = lock.release()
    }
}
