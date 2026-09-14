package ru.hznik.devicebridge.diagnostics.server

sealed interface ServerState {
    data object Stopped : ServerState

    data object Starting : ServerState

    data class Running(
        val address: String,
        val port: Int,
        val token: String,
    ) : ServerState

    data object Stopping : ServerState

    data class Error(
        val message: String,
    ) : ServerState
}
