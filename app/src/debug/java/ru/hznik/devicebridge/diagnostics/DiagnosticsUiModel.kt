package ru.hznik.devicebridge.diagnostics

import ru.hznik.devicebridge.diagnostics.server.ServerState

data class DiagnosticsUiModel(
    val statusLabel: String,
    val supportingText: String,
    val address: String?,
    val token: String?,
    val errorMessage: String?,
    val primaryActionLabel: String,
    val primaryActionEnabled: Boolean,
    val isRunning: Boolean,
    val isBusy: Boolean,
)

fun toDiagnosticsUiModel(state: ServerState): DiagnosticsUiModel {
    return when (state) {
        ServerState.Stopped -> DiagnosticsUiModel(
            statusLabel = "Сервер остановлен",
            supportingText = "Диагностический порт закрыт.",
            address = null,
            token = null,
            errorMessage = null,
            primaryActionLabel = "Запустить сервер",
            primaryActionEnabled = true,
            isRunning = false,
            isBusy = false,
        )

        ServerState.Starting -> DiagnosticsUiModel(
            statusLabel = "Сервер запускается",
            supportingText = "CIO занимает локальный порт.",
            address = null,
            token = null,
            errorMessage = null,
            primaryActionLabel = "Запуск…",
            primaryActionEnabled = false,
            isRunning = false,
            isBusy = true,
        )

        is ServerState.Running -> DiagnosticsUiModel(
            statusLabel = "Сервер запущен",
            supportingText = "Диагностические маршруты доступны только с токеном.",
            address = "http://" + state.address + ":" + state.port,
            token = state.token,
            errorMessage = null,
            primaryActionLabel = "Остановить сервер",
            primaryActionEnabled = true,
            isRunning = true,
            isBusy = false,
        )

        ServerState.Stopping -> DiagnosticsUiModel(
            statusLabel = "Сервер останавливается",
            supportingText = "Соединения и порт освобождаются.",
            address = null,
            token = null,
            errorMessage = null,
            primaryActionLabel = "Остановка…",
            primaryActionEnabled = false,
            isRunning = false,
            isBusy = true,
        )

        is ServerState.Error -> DiagnosticsUiModel(
            statusLabel = "Ошибка запуска",
            supportingText = "Исправьте причину и повторите запуск.",
            address = null,
            token = null,
            errorMessage = state.message,
            primaryActionLabel = "Повторить запуск",
            primaryActionEnabled = true,
            isRunning = false,
            isBusy = false,
        )
    }
}
