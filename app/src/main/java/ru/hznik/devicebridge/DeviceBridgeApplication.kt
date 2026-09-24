package ru.hznik.devicebridge

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import ru.hznik.devicebridge.data.file.PartialUploadCleanup
import kotlinx.coroutines.CoroutineScope
import ru.hznik.devicebridge.di.ApplicationScope
import ru.hznik.devicebridge.server.AppVisibilityTracker
import ru.hznik.devicebridge.server.EventNotificationCoordinator
import ru.hznik.devicebridge.server.ServerTileRefresher

@HiltAndroidApp
class DeviceBridgeApplication : Application() {
    @Inject
    lateinit var serverTileRefresher: ServerTileRefresher

    @Inject
    lateinit var partialUploadCleanup: PartialUploadCleanup

    @Inject
    lateinit var appVisibilityTracker: AppVisibilityTracker

    @Inject
    lateinit var eventNotifications: EventNotificationCoordinator

    @Inject
    @field:ApplicationScope
    lateinit var applicationScope: CoroutineScope

    override fun onCreate() {
        super.onCreate()
        registerActivityLifecycleCallbacks(appVisibilityTracker)
        serverTileRefresher.start(this)
        eventNotifications.start(applicationScope)
        partialUploadCleanup.run()
    }
}
