package ru.hznik.devicebridge.data.tls

import java.nio.file.Files
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.hznik.devicebridge.domain.model.ServerEndpoint
import ru.hznik.devicebridge.domain.model.ServerLifecycleState
import ru.hznik.devicebridge.domain.model.ServerStopReason
import ru.hznik.devicebridge.domain.repository.ServerLifecycleRepository
import ru.hznik.devicebridge.domain.settings.DeviceSettings
import ru.hznik.devicebridge.domain.settings.SettingsUpdateResult

class SecureModeControllerTest {

    private val lifecycle = RecordingLifecycle()
    private val calls = mutableListOf<String>()
    private val authority = LocalCertificateAuthority(
        SoftwareTlsKeyStore(),
        Files.createTempDirectory("secure-mode").toFile(),
    )

    @Test
    fun enablingWhileStoppedCreatesTheRootWithoutStartingTheServer() = runTest {
        val controller = controller(StandardTestDispatcher(testScheduler))

        assertEquals(RootCertificateStatus.NotCreated, controller.rootStatus())
        controller.setEnabled(true)

        assertEquals(listOf("setting true", "applied true"), calls)
        assertTrue(controller.rootStatus() is RootCertificateStatus.Ready)
        assertEquals(emptyList<String>(), lifecycle.commands)
    }

    @Test
    fun changingTheModeWhileRunningRestartsOnceAfterTheSettingApplies() = runTest {
        lifecycle.state.value = RUNNING
        val controller = controller(StandardTestDispatcher(testScheduler))

        assertTrue(controller.changeRestartsServer())
        controller.setEnabled(false)

        assertEquals(listOf("setting false", "applied false", "stop", "start"), calls)
        assertEquals(listOf("stop UserRequested", "start"), lifecycle.commands)
    }

    @Test
    fun resetGivesANewRootAndRestartsARunningServer() = runTest {
        val controller = controller(StandardTestDispatcher(testScheduler))
        val before = (controller.also { it.setEnabled(true) }.rootStatus() as RootCertificateStatus.Ready)
        lifecycle.state.value = RUNNING
        calls.clear()

        val after = controller.resetCertificate() as RootCertificateStatus.Ready

        assertFalse(before.fingerprints == after.fingerprints)
        assertEquals(listOf("stop", "start"), calls)
    }

    private fun controller(io: kotlinx.coroutines.CoroutineDispatcher) = SecureModeController(
        updateSetting = { enabled ->
            calls += "setting $enabled"
            SettingsUpdateResult.Updated(DeviceSettings.defaults().copy(secureModeEnabled = enabled))
        },
        awaitSettingApplied = { enabled -> calls += "applied $enabled" },
        lifecycle = lifecycle,
        authority = authority,
        io = io,
    )

    private inner class RecordingLifecycle : ServerLifecycleRepository {
        override val state = MutableStateFlow<ServerLifecycleState>(ServerLifecycleState.Stopped)
        override val lastStopReason: StateFlow<ServerStopReason?> = MutableStateFlow(null)
        val commands = mutableListOf<String>()

        override suspend fun start() {
            calls += "start"
            commands += "start"
            state.value = RUNNING
        }

        override suspend fun stop(reason: ServerStopReason) {
            calls += "stop"
            commands += "stop $reason"
            state.value = ServerLifecycleState.Stopped
        }
    }

    private companion object {
        val RUNNING = ServerLifecycleState.Running(1, ServerEndpoint("192.168.1.24", 8_787), 0)
    }
}
