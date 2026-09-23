package ru.hznik.devicebridge.server

import ru.hznik.devicebridge.domain.file.FileTransferSnapshot
import ru.hznik.devicebridge.domain.model.ServerLifecycleState
import ru.hznik.devicebridge.domain.text.TextTransferState
import ru.hznik.devicebridge.domain.text.TextTransferStatus

enum class ServerTileAppearance {
    ACTIVE,
    INACTIVE,
    UNAVAILABLE,
}

data class ServerTileModel(
    val appearance: ServerTileAppearance,
    val subtitle: String,
)

sealed interface ServerTileClick {
    /** Start the foreground service straight from the tile. */
    data object StartDirectly : ServerTileClick

    /** Collapse the shade and let the visible Home screen start (permissions, FGS limits). */
    data object OpenAppToStart : ServerTileClick

    /** Ask the user to unlock, then evaluate the click again. */
    data object UnlockFirst : ServerTileClick

    data object Stop : ServerTileClick

    /** A transfer is running: confirm before interrupting it. */
    data object ConfirmStop : ServerTileClick

    /** Starting or stopping is already in progress. */
    data object Ignore : ServerTileClick
}

object ServerTilePolicy {
    fun model(state: ServerLifecycleState, browserCount: Int): ServerTileModel = when (state) {
        is ServerLifecycleState.Running -> ServerTileModel(
            ServerTileAppearance.ACTIVE,
            "Запущен · ${formatBrowserCount(browserCount)}",
        )
        is ServerLifecycleState.Starting ->
            ServerTileModel(ServerTileAppearance.UNAVAILABLE, "Запускается…")
        is ServerLifecycleState.Stopping ->
            ServerTileModel(ServerTileAppearance.UNAVAILABLE, "Останавливается…")
        ServerLifecycleState.Stopped,
        is ServerLifecycleState.Error,
        -> ServerTileModel(ServerTileAppearance.INACTIVE, "Остановлен")
    }

    fun click(
        state: ServerLifecycleState,
        deviceLocked: Boolean,
        canStartWithoutScreen: Boolean,
        hasActiveOperations: Boolean,
    ): ServerTileClick = when (state) {
        is ServerLifecycleState.Running ->
            if (hasActiveOperations) ServerTileClick.ConfirmStop else ServerTileClick.Stop
        is ServerLifecycleState.Starting,
        is ServerLifecycleState.Stopping,
        -> ServerTileClick.Ignore
        ServerLifecycleState.Stopped,
        is ServerLifecycleState.Error,
        -> when {
            deviceLocked -> ServerTileClick.UnlockFirst
            !canStartWithoutScreen -> ServerTileClick.OpenAppToStart
            else -> ServerTileClick.StartDirectly
        }
    }

    fun hasActiveOperations(texts: TextTransferState, files: FileTransferSnapshot): Boolean =
        texts.items.any {
            it.status == TextTransferStatus.PENDING || it.status == TextTransferStatus.SENDING
        } || files.items.any { !it.phase.isTerminal }

    internal fun formatBrowserCount(count: Int): String {
        val lastTwo = count % 100
        val last = count % 10
        val word = when {
            lastTwo in 11..14 -> "браузеров"
            last == 1 -> "браузер"
            last in 2..4 -> "браузера"
            else -> "браузеров"
        }
        return "$count $word"
    }
}
