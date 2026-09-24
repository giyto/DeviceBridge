package ru.hznik.devicebridge

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import ru.hznik.devicebridge.data.file.PartialUploadCleanup
import ru.hznik.devicebridge.server.ServerTileRefresher

@HiltAndroidApp
class DeviceBridgeApplication : Application() {
    @Inject
    lateinit var serverTileRefresher: ServerTileRefresher

    @Inject
    lateinit var partialUploadCleanup: PartialUploadCleanup

    override fun onCreate() {
        super.onCreate()
        serverTileRefresher.start(this)
        partialUploadCleanup.run()
    }
}
