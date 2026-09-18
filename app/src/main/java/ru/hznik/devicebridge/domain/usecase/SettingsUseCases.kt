package ru.hznik.devicebridge.domain.usecase

import ru.hznik.devicebridge.domain.repository.SettingsRepository
import ru.hznik.devicebridge.domain.repository.BrowserSessionRepository
import ru.hznik.devicebridge.domain.repository.TrustedBrowserRepository
import ru.hznik.devicebridge.domain.settings.DestinationTree
import ru.hznik.devicebridge.domain.trust.TrustedBrowserId

class ObserveSettingsUseCase(
    private val repository: SettingsRepository,
) {
    operator fun invoke() = repository.settings
}

class UpdateDeviceNameUseCase(
    private val repository: SettingsRepository,
) {
    suspend operator fun invoke(value: String) = repository.updateDeviceName(value)
}

class UpdateRetentionDaysUseCase(
    private val repository: SettingsRepository,
) {
    suspend operator fun invoke(value: Int) = repository.updateRetentionDays(value)
}

class UpdateDestinationTreeUseCase(
    private val repository: SettingsRepository,
) {
    suspend operator fun invoke(value: DestinationTree?) =
        repository.updateDestinationTree(value)
}

class UpdateFileLimitUseCase(
    private val repository: SettingsRepository,
) {
    suspend operator fun invoke(value: Long) =
        repository.updateEffectiveFileLimitBytes(value)
}

class ObserveTrustedBrowsersUseCase(
    private val repository: TrustedBrowserRepository,
) {
    operator fun invoke() = repository.trustedBrowsers
}

class RevokeTrustedBrowserUseCase(
    private val repository: BrowserSessionRepository,
) {
    suspend operator fun invoke(browserId: TrustedBrowserId): Boolean =
        repository.revokeTrustedBrowser(browserId)
}

class RevokeAllTrustedBrowsersUseCase(
    private val repository: BrowserSessionRepository,
) {
    suspend operator fun invoke(): Int = repository.revokeAllTrustedBrowsers()
}
