package ru.hznik.devicebridge.data.server

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import ru.hznik.devicebridge.domain.repository.SettingsRepository
import ru.hznik.devicebridge.domain.settings.DestinationTree
import ru.hznik.devicebridge.domain.settings.DeviceSettings
import ru.hznik.devicebridge.domain.settings.SettingsUpdateResult
import ru.hznik.devicebridge.domain.usecase.ObserveSettingsUseCase

class EffectiveFileLimitProviderTest {
    @Test
    fun physicalNameIsAvailableBeforeDataStoreEmits() = runTest {
        val provider = EffectiveFileLimitProvider(
            observeSettings = ObserveSettingsUseCase(NeverEmittingSettingsRepository),
            applicationScope = backgroundScope,
            initialDeviceName = "Xiaomi 22081212UG",
        )

        assertEquals("Xiaomi 22081212UG", provider.currentDeviceName())
    }

    private object NeverEmittingSettingsRepository : SettingsRepository {
        override val settings: Flow<DeviceSettings> = emptyFlow()

        override suspend fun updateDeviceName(value: String): SettingsUpdateResult =
            error("Not used")

        override suspend fun updateRetentionDays(value: Int): SettingsUpdateResult =
            error("Not used")

        override suspend fun updateDestinationTree(value: DestinationTree?): SettingsUpdateResult =
            error("Not used")

        override suspend fun updateEffectiveFileLimitBytes(value: Long): SettingsUpdateResult =
            error("Not used")

        override suspend fun updateAutoAcceptTrustedFiles(enabled: Boolean): SettingsUpdateResult =
            error("Not used")

        override suspend fun updateIdleStopTimeout(
            value: ru.hznik.devicebridge.domain.settings.IdleStopTimeout,
        ): SettingsUpdateResult = error("Not used")
    }
}
