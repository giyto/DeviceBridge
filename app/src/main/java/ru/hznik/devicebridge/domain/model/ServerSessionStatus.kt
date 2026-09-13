package ru.hznik.devicebridge.domain.model

/**
 * Platform-independent lifecycle state of the local DeviceBridge server.
 *
 * Only the honest stopped state exists until the embedded-server change is
 * implemented. New states can be added here without leaking Android details
 * into the domain model.
 */
sealed interface ServerSessionStatus {
    val isRunning: Boolean

    data object Stopped : ServerSessionStatus {
        override val isRunning: Boolean = false
    }
}
