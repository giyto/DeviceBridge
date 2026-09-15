package ru.hznik.devicebridge.server

import javax.inject.Inject
import ru.hznik.devicebridge.domain.model.ServerLifecycleState

data class ServerNotificationModel(
    val title: String,
    val text: String,
    val ongoing: Boolean,
    val showStopAction: Boolean,
)

class ServerNotificationModelFactory @Inject constructor() {

    fun create(state: ServerLifecycleState): ServerNotificationModel? = when (state) {
        ServerLifecycleState.Stopped,
        is ServerLifecycleState.Error,
        -> null

        is ServerLifecycleState.Starting -> ServerNotificationModel(
            title = "Сервер запускается",
            text = "Подготовка локального подключения",
            ongoing = true,
            showStopAction = true,
        )

        is ServerLifecycleState.Running -> ServerNotificationModel(
            title = "Сервер запущен",
            text = state.endpoint.url + " • 0 браузеров • Передача не выполняется",
            ongoing = true,
            showStopAction = true,
        )

        is ServerLifecycleState.Stopping -> ServerNotificationModel(
            title = "Сервер останавливается",
            text = "Закрываем локальное подключение",
            ongoing = true,
            showStopAction = false,
        )
    }
}
