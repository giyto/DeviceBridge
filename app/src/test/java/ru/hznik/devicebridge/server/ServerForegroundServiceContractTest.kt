package ru.hznik.devicebridge.server

import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerForegroundServiceContractTest {

    private val source = Files.readString(
        Path.of(
            "src/main/java/ru/hznik/devicebridge/server/ServerForegroundService.kt",
        ),
    )

    @Test
    fun entersConnectedDeviceForegroundBeforeStartingCoordinator() {
        val foregroundIndex = source.indexOf("ServiceCompat.startForeground")
        val coordinatorStartIndex = source.indexOf("coordinator.start()")

        assertTrue(foregroundIndex >= 0)
        assertTrue(coordinatorStartIndex > foregroundIndex)
        assertTrue(source.contains("FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE"))
        assertTrue(source.contains("START_NOT_STICKY"))
    }

    @Test
    fun stopActionUsesSameCoordinatorAndFullyTearsDownService() {
        assertTrue(source.contains("ACTION_STOP"))
        assertTrue(source.contains("coordinator.stop"))
        assertTrue(source.contains("STOP_FOREGROUND_REMOVE"))
        assertTrue(source.contains("notificationController.cancel()"))
        assertTrue(source.contains("stopSelf"))
        assertFalse(source.contains("runBlocking"))
    }

    @Test
    fun lifecycleNotificationsComeFromProcessWideCoordinatorState() {
        assertTrue(source.contains("combine("))
        assertTrue(source.contains("coordinator.state"))
        assertTrue(source.contains("browserSessionRepository.state"))
        assertTrue(source.contains("notificationController.publish(state)"))
        assertFalse(source.contains("savedInstanceState"))
    }

    @Test
    fun api31ForegroundStartExceptionIsCheckedBehindVersionGuard() {
        assertFalse(
            source.contains(
                "catch (notAllowed: ForegroundServiceStartNotAllowedException)",
            ),
        )
        assertTrue(source.contains("Build.VERSION.SDK_INT >= Build.VERSION_CODES.S"))
        assertTrue(source.contains("@RequiresApi(Build.VERSION_CODES.S)"))
        assertTrue(source.contains("ForegroundServiceStartNotAllowedException"))
    }

    @Test
    fun staleStopCannotTearDownAServiceStartedByANewerCommand() {
        assertTrue(source.contains("latestStartId = startId"))
        assertTrue(source.contains("finishForegroundService(stopStartId"))
        assertTrue(source.contains("if (latestStartId != stopStartId)"))
        assertTrue(source.contains("stopSelfResult(stopStartId)"))
        assertFalse(
            source.contains(
                "if (foregroundStarted) {\n            return START_NOT_STICKY",
            ),
        )
    }

    @Test
    fun repeatedStopFinishesWithTheNewestPendingStopCommand() {
        assertTrue(source.contains("pendingStopStartId = startId"))
        assertTrue(source.contains("pendingStopStartId = null"))
        assertTrue(source.contains("pendingStopStartId?.let"))

        val finishSource = source.substringAfter(
            "private fun finishForegroundService(stopStartId: Int)",
        )
        assertTrue(finishSource.contains("pendingStopStartId = null"))
    }
}
