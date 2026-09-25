package ru.hznik.devicebridge.feature.home

import ru.hznik.devicebridge.domain.model.LocalNameStatus
import ru.hznik.devicebridge.domain.model.ServerEndpoint

/** Why the address is not by the name the person chose, and whether the settings can fix it. */
data class LocalNameNotice(
    val text: String,
    val opensSettings: Boolean,
)

fun ServerEndpoint.localNameNotice(): LocalNameNotice? = when (val status = nameStatus) {
    LocalNameStatus.NotUsed -> null

    is LocalNameStatus.Claimed ->
        if (status.requestedTaken) {
            LocalNameNotice(
                text = "Имя «${status.requestedLabel}» уже занято другим устройством в сети, поэтому " +
                    "телефон взял «${localName?.removeSuffix(".local")}». Задайте своё имя в настройках, " +
                    "чтобы адрес не менялся.",
                opensSettings = true,
            )
        } else {
            null
        }

    is LocalNameStatus.Unavailable -> {
        val label = status.requestedLabel ?: "devicebridge"
        when (status.reason) {
            LocalNameStatus.Reason.TAKEN -> LocalNameNotice(
                text = "Имя «$label» и его варианты заняты другими устройствами в сети. Работает адрес " +
                    "по IP. Задайте своё имя в настройках.",
                opensSettings = true,
            )

            LocalNameStatus.Reason.CONFLICT -> LocalNameNotice(
                text = "Другое устройство в сети начало отвечать на имя «$label». До перезапуска сервера " +
                    "работает адрес по IP. Чтобы такого не было, задайте своё имя в настройках.",
                opensSettings = true,
            )

            LocalNameStatus.Reason.NETWORK -> LocalNameNotice(
                text = "Адрес по имени сейчас недоступен: телефон или сеть не пропускают такие запросы. " +
                    "Работает адрес по IP.",
                opensSettings = false,
            )

            LocalNameStatus.Reason.CERTIFICATE -> LocalNameNotice(
                text = "Сертификат на компьютере подходит только для адреса devicebridge.local. Чтобы " +
                    "открыть защищённую страницу по имени «$label.local», сбросьте сертификат в настройках " +
                    "и установите его на компьютер заново.",
                opensSettings = true,
            )
        }
    }
}
