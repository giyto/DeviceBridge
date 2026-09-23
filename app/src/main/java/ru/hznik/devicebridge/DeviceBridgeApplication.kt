package ru.hznik.devicebridge

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import ru.hznik.devicebridge.server.ServerTileRefresher

@HiltAndroidApp
class DeviceBridgeApplication : Application() {
    @Inject
    lateinit var serverTileRefresher: ServerTileRefresher

    override fun onCreate() {
        super.onCreate()
        serverTileRefresher.start(this)
    }
}
