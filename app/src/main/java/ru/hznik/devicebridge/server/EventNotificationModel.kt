package ru.hznik.devicebridge.server

import android.os.Build
import javax.inject.Inject
import ru.hznik.devicebridge.core.text.ruPlural
import ru.hznik.devicebridge.domain.file.FileTransferDirection
import ru.hznik.devicebridge.domain.file.FileTransferId
import ru.hznik.devicebridge.domain.session.BrowserSessionId
import ru.hznik.devicebridge.domain.session.PairingRequestId
import ru.hznik.devicebridge.domain.text.TextMessageId
import ru.hznik.devicebridge.feature.file.formatBytes

/** Identifies one event notification; never shown and never put into an intent. */
sealed interface EventNotificationKey {
    data class Pairing(val requestId: PairingRequestId) : EventNotificationKey
    data class IncomingFiles(val sessionId: BrowserSessionId) : EventNotificationKey
    data class Text(val sessionId: BrowserSessionId, val messageId: TextMessageId) : EventNotificationKey
    data class TransferResult(
        val sessionId: BrowserSessionId,
        val direction: FileTransferDirection,
    ) : EventNotificationKey
}

/** What happened, before it is turned into words. */
sealed interface EventNotice {
    val key: EventNotificationKey

    data class PairingRequest(
        val requestId: PairingRequestId,
        val browserLabel: String,
        val remainingMs: Long,
        /** The browser asked to be remembered, so "Разрешить и запомнить" is offered too. */
        val rememberRequested: Boolean = false,
    ) : EventNotice {
        override val key get() = EventNotificationKey.Pairing(requestId)
    }

    data class IncomingFiles(
        val sessionId: BrowserSessionId,
        val transferIds: List<FileTransferId>,
        val firstName: String,
        val totalBytes: Long,
        /** A folder for incoming files is chosen and nothing in the offer is waiting for a new one. */
        val acceptable: Boolean,
    ) : EventNotice {
        override val key get() = EventNotificationKey.IncomingFiles(sessionId)
    }

    data class IncomingText(
        val sessionId: BrowserSessionId,
        val messageId: TextMessageId,
        val content: String,
        val isLink: Boolean,
    ) : EventNotice {
        override val key get() = EventNotificationKey.Text(sessionId, messageId)
    }

    data class TransferResult(
        val sessionId: BrowserSessionId,
        val direction: FileTransferDirection,
        val completed: Int,
        val failed: Int,
        val cancelled: Int,
    ) : EventNotice {
        override val key get() = EventNotificationKey.TransferResult(sessionId, direction)
    }
}

enum class EventNotificationAction(
    val label: String,
    /** Android asks to unlock the phone before the button acts (Android 12+). */
    val requiresUnlock: Boolean = false,
) {
    OPEN("Открыть"),
    ACCEPT_FILES("Принять"),
    DECLINE_FILES("Отклонить"),
    COPY_TEXT("Копировать"),
    OPEN_LINK("Открыть"),
    SHOW_FILES("Показать"),
    DENY_PAIRING("Отклонить"),
    ALLOW_PAIRING("Разрешить", requiresUnlock = true),
    ALLOW_AND_REMEMBER_PAIRING("Разрешить и запомнить", requiresUnlock = true),
}

/** The screen a tap on the notification opens. */
enum class EventNotificationTarget(val section: String) {
    HOME("home"),
    TEXT("text"),
    FILES("files"),
}

data class EventNotificationModel(
    val title: String,
    val text: String,
    /** All the lock screen shows: the kind of event, no content. */
    val publicTitle: String,
    val actions: List<EventNotificationAction>,
    val target: EventNotificationTarget,
    val timeoutMs: Long? = null,
    /** The link "Открыть" opens; set only for a link the app considers safe. */
    val link: String? = null,
)

/**
 * [sdkInt] decides the pairing buttons: only Android 12+ can demand an unlock before a button
 * acts, so older phones allow a connection only in the app.
 */
class EventNotificationModelFactory internal constructor(private val sdkInt: Int) {

    @Inject
    constructor() : this(Build.VERSION.SDK_INT)


    fun create(notice: EventNotice): EventNotificationModel = when (notice) {
        is EventNotice.PairingRequest -> {
            val canAllow = sdkInt >= Build.VERSION_CODES.S
            EventNotificationModel(
                title = "Запрос подключения",
                text = "${notice.browserLabel.safeLabel()} просит доступ к телефону." +
                    if (canAllow) "" else " Откройте DeviceBridge, чтобы разрешить.",
                publicTitle = "Запрос подключения",
                actions = buildList {
                    add(EventNotificationAction.DENY_PAIRING)
                    if (canAllow) {
                        add(EventNotificationAction.ALLOW_PAIRING)
                        if (notice.rememberRequested) add(EventNotificationAction.ALLOW_AND_REMEMBER_PAIRING)
                    }
                },
                target = EventNotificationTarget.HOME,
                timeoutMs = notice.remainingMs.coerceAtLeast(1),
            )
        }

        is EventNotice.IncomingFiles -> {
            val count = notice.transferIds.size
            val names = notice.firstName.safeNotificationName() +
                if (count > 1) " и ещё ${count - 1}" else ""
            EventNotificationModel(
                title = "${countOfFiles(count)} с компьютера",
                text = "$names - ${formatBytes(notice.totalBytes)}" +
                    if (notice.acceptable) "" else "\nОткройте приложение, чтобы выбрать папку.",
                publicTitle = "Файлы с компьютера",
                actions = if (notice.acceptable) {
                    listOf(EventNotificationAction.ACCEPT_FILES, EventNotificationAction.DECLINE_FILES)
                } else {
                    listOf(EventNotificationAction.OPEN)
                },
                target = EventNotificationTarget.FILES,
            )
        }

        is EventNotice.IncomingText -> EventNotificationModel(
            title = if (notice.isLink) "Ссылка с компьютера" else "Текст с компьютера",
            text = notice.content.preview(),
            publicTitle = "Текст с компьютера",
            actions = if (notice.isLink) {
                listOf(EventNotificationAction.COPY_TEXT, EventNotificationAction.OPEN_LINK)
            } else {
                listOf(EventNotificationAction.COPY_TEXT)
            },
            target = EventNotificationTarget.TEXT,
            link = notice.content.takeIf { notice.isLink },
        )

        is EventNotice.TransferResult -> resultModel(notice)
    }

    private fun resultModel(notice: EventNotice.TransferResult): EventNotificationModel {
        val incoming = notice.direction == FileTransferDirection.BROWSER_TO_ANDROID
        val total = notice.completed + notice.failed + notice.cancelled
        val text = if (total == notice.completed) {
            val verb = if (incoming) {
                if (isSingular(total)) "Сохранён" else "Сохранено"
            } else {
                if (isSingular(total)) "Передан" else "Передано"
            }
            "$verb ${countOfFiles(total)}" + if (incoming) "" else " на компьютер"
        } else {
            (if (incoming) "Сохранено" else "Передано") + " ${notice.completed} из $total" +
                (if (notice.failed > 0) ", ${notice.failed} не удалось" else "") +
                if (notice.cancelled > 0) ", ${notice.cancelled} отменено" else ""
        }
        return EventNotificationModel(
            title = when {
                notice.failed > 0 -> "Передача прервалась"
                incoming -> "Файлы сохранены"
                else -> "Файлы переданы"
            },
            text = text,
            publicTitle = "Передача завершена",
            actions = listOf(EventNotificationAction.SHOW_FILES),
            target = EventNotificationTarget.FILES,
        )
    }

    private fun String.preview(): String {
        val clean = filterNot { it in BIDI_CONTROL_CHARACTERS }
            .map { if (it.isISOControl() && it != '\n') ' ' else it }
            .joinToString("")
            .trim()
        return if (clean.length > PREVIEW_LENGTH) clean.take(PREVIEW_LENGTH).trimEnd() + "…" else clean
    }

    private fun String.safeLabel(): String =
        filterNot { it.isISOControl() || it in BIDI_CONTROL_CHARACTERS }.trim().take(64).ifBlank { "Браузер" }

    private companion object {
        const val PREVIEW_LENGTH = 500
    }
}

internal fun countOfFiles(count: Int): String =
    "$count ${ruPlural(count, "файл", "файла", "файлов")}"

private fun isSingular(count: Int): Boolean = count % 10 == 1 && count % 100 != 11
