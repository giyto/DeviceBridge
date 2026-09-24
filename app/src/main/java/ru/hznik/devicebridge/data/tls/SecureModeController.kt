package ru.hznik.devicebridge.data.tls

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import ru.hznik.devicebridge.domain.model.ServerLifecycleState
import ru.hznik.devicebridge.domain.model.ServerStopReason
import ru.hznik.devicebridge.domain.repository.ServerLifecycleRepository
import ru.hznik.devicebridge.domain.settings.SettingsUpdateResult

/** What the settings screen can show about the phone's root certificate. */
sealed interface RootCertificateStatus {
    /** Secure mode has never been turned on, so there is no root yet. */
    data object NotCreated : RootCertificateStatus

    data class Ready(val fingerprints: CertificateFingerprints) : RootCertificateStatus

    /** The root or its key cannot be used; only a reset helps. */
    data object Unusable : RootCertificateStatus
}

/**
 * Changes that affect how the server is reached. A running server is restarted so browsers
 * move to the new transport at once; its current sessions end, while remembered browsers stay.
 */
class SecureModeController(
    private val updateSetting: suspend (Boolean) -> SettingsUpdateResult,
    /** Suspends until the server runtime reads the new value, so a restart picks it up. */
    private val awaitSettingApplied: suspend (Boolean) -> Unit,
    private val lifecycle: ServerLifecycleRepository,
    private val authority: LocalCertificateAuthority,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val restartWaitMillis: Long = RESTART_WAIT_MILLIS,
) {
    /** Whether a change now would restart the server and disconnect its browsers. */
    fun changeRestartsServer(): Boolean = lifecycle.state.value.isRunning

    suspend fun setEnabled(enabled: Boolean): SettingsUpdateResult {
        val restart = changeRestartsServer()
        if (enabled) {
            // Created now so the fingerprint can be shown before the server first starts.
            runCatching { withContext(io) { authority.ensureRoot() } }
        }
        val result = updateSetting(enabled)
        if (result is SettingsUpdateResult.Updated) {
            awaitSettingApplied(enabled)
            if (restart) restart()
        }
        return result
    }

    suspend fun resetCertificate(): RootCertificateStatus {
        val restart = changeRestartsServer()
        val status = withContext(io) {
            runCatching { RootCertificateStatus.Ready(CertificateFingerprints.of(authority.reset())) }
                .getOrElse { RootCertificateStatus.Unusable }
        }
        if (restart) restart()
        return status
    }

    suspend fun rootStatus(): RootCertificateStatus = withContext(io) {
        runCatching {
            authority.rootOrNull()
                ?.let { RootCertificateStatus.Ready(CertificateFingerprints.of(it)) }
                ?: RootCertificateStatus.NotCreated
        }.getOrElse { RootCertificateStatus.Unusable }
    }

    private suspend fun restart() {
        lifecycle.stop(ServerStopReason.UserRequested)
        withTimeoutOrNull(restartWaitMillis) {
            lifecycle.state.first {
                it is ServerLifecycleState.Stopped || it is ServerLifecycleState.Error
            }
        }
        lifecycle.start()
    }

    private companion object {
        const val RESTART_WAIT_MILLIS = 10_000L
    }
}
