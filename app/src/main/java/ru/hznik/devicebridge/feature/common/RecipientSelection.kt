package ru.hznik.devicebridge.feature.common

import ru.hznik.devicebridge.domain.session.BrowserSession
import ru.hznik.devicebridge.domain.session.BrowserSessionId

/** A connected browser that text or files can be sent to. */
data class RecipientUiState(
    val id: BrowserSessionId,
    val browserLabel: String,
    val sourceIpv4: String,
    val selected: Boolean,
)

internal const val RECIPIENT_DISCONNECTED_MESSAGE =
    "Выбранный браузер отключён. Выберите получателя."

/** How the chosen recipient follows a change in the connected browsers. */
internal sealed interface RecipientReconciliation {
    data object Unchanged : RecipientReconciliation

    /** The chosen browser went away; the screen asks for a new one. */
    data object Lost : RecipientReconciliation

    /** Nothing was chosen and only one browser is connected, so it is chosen. */
    data class AutoSelected(val sessionId: BrowserSessionId) : RecipientReconciliation
}

internal fun reconcileRecipientChoice(
    selectedSessionId: BrowserSessionId?,
    recipientWasLost: Boolean,
    active: List<BrowserSession>,
): RecipientReconciliation = when {
    selectedSessionId != null && active.none { it.id == selectedSessionId } ->
        RecipientReconciliation.Lost
    selectedSessionId == null && active.size == 1 && !recipientWasLost ->
        RecipientReconciliation.AutoSelected(active.single().id)
    else -> RecipientReconciliation.Unchanged
}

/** The chosen recipient while its browser is still connected, otherwise null. */
internal fun List<BrowserSession>.connectedRecipient(selectedSessionId: BrowserSessionId?): BrowserSessionId? =
    selectedSessionId?.takeIf { id -> any { it.id == id } }

internal fun List<BrowserSession>.toRecipients(selected: BrowserSessionId?): List<RecipientUiState> =
    map { session ->
        RecipientUiState(
            id = session.id,
            browserLabel = session.browserLabel,
            sourceIpv4 = session.sourceIpv4,
            selected = session.id == selected,
        )
    }
