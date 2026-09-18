package ru.hznik.devicebridge.domain.session

sealed interface BrowserSessionEvent {
    data class Activated(
        val generationId: ServerGenerationId,
        val pairingCode: PairingCodeState,
    ) : BrowserSessionEvent

    data class PairingCodeRotated(
        val generationId: ServerGenerationId,
        val pairingCode: PairingCodeState,
    ) : BrowserSessionEvent

    data class RequestAdded(val request: PendingBrowserRequest) : BrowserSessionEvent

    data class RequestDenied(
        val generationId: ServerGenerationId,
        val requestId: PairingRequestId,
    ) : BrowserSessionEvent

    data class RequestExpired(
        val generationId: ServerGenerationId,
        val requestId: PairingRequestId,
    ) : BrowserSessionEvent

    data class RequestApproved(
        val generationId: ServerGenerationId,
        val requestId: PairingRequestId,
        val session: BrowserSession,
        val nextPairingCode: PairingCodeState,
    ) : BrowserSessionEvent

    data class SessionRevoked(
        val generationId: ServerGenerationId,
        val sessionId: BrowserSessionId,
    ) : BrowserSessionEvent

    data class TrustedSessionConnected(
        val generationId: ServerGenerationId,
        val session: BrowserSession,
    ) : BrowserSessionEvent

    data class SourceBlocked(
        val generationId: ServerGenerationId,
        val blockedUntilElapsedRealtimeMs: Long,
    ) : BrowserSessionEvent

    data class SourceBlockCleared(val generationId: ServerGenerationId) : BrowserSessionEvent

    data class Failed(
        val generationId: ServerGenerationId,
        val error: BrowserSessionError,
    ) : BrowserSessionEvent

    data class Deactivated(val generationId: ServerGenerationId) : BrowserSessionEvent
}

object BrowserSessionReducer {

    fun reduce(
        current: BrowserSessionState,
        event: BrowserSessionEvent,
    ): BrowserSessionState = when (event) {
        is BrowserSessionEvent.Activated -> BrowserSessionState.active(
            generationId = event.generationId,
            pairingCode = event.pairingCode,
        )

        is BrowserSessionEvent.PairingCodeRotated -> current.ifGeneration(event.generationId) {
            evolve(pairingCode = event.pairingCode, error = null)
        }

        is BrowserSessionEvent.RequestAdded -> current.ifGeneration(event.request.generationId) {
            if (pendingRequests.any { it.id == event.request.id }) {
                this
            } else {
                evolve(pendingRequests = pendingRequests + event.request, error = null)
            }
        }

        is BrowserSessionEvent.RequestDenied -> current.removeRequest(
            generationId = event.generationId,
            requestId = event.requestId,
        )

        is BrowserSessionEvent.RequestExpired -> current.removeRequest(
            generationId = event.generationId,
            requestId = event.requestId,
        )

        is BrowserSessionEvent.RequestApproved -> current.ifGeneration(event.generationId) {
            val requestExists = pendingRequests.any { it.id == event.requestId }
            val sessionIsValid = event.session.generationId == generationId &&
                sessions.none { it.id == event.session.id }
            if (!requestExists || !sessionIsValid) {
                this
            } else {
                evolve(
                    pairingCode = event.nextPairingCode,
                    pendingRequests = pendingRequests.filterNot { it.id == event.requestId },
                    sessions = sessions + event.session,
                    error = null,
                )
            }
        }

        is BrowserSessionEvent.SessionRevoked -> current.ifGeneration(event.generationId) {
            if (sessions.none { it.id == event.sessionId }) {
                this
            } else {
                evolve(sessions = sessions.filterNot { it.id == event.sessionId })
            }
        }

        is BrowserSessionEvent.TrustedSessionConnected -> current.ifGeneration(event.generationId) {
            if (
                event.session.generationId != generationId ||
                sessions.any { it.id == event.session.id }
            ) {
                this
            } else {
                evolve(sessions = sessions + event.session, error = null)
            }
        }

        is BrowserSessionEvent.SourceBlocked -> current.ifGeneration(event.generationId) {
            evolve(blockedUntilElapsedRealtimeMs = event.blockedUntilElapsedRealtimeMs)
        }

        is BrowserSessionEvent.SourceBlockCleared -> current.ifGeneration(event.generationId) {
            if (blockedUntilElapsedRealtimeMs == null) this else evolve(blockedUntilElapsedRealtimeMs = null)
        }

        is BrowserSessionEvent.Failed -> current.ifGeneration(event.generationId) {
            evolve(error = event.error)
        }

        is BrowserSessionEvent.Deactivated -> current.ifGeneration(event.generationId) {
            BrowserSessionState.inactive()
        }
    }

    private inline fun BrowserSessionState.ifGeneration(
        expected: ServerGenerationId,
        transform: BrowserSessionState.() -> BrowserSessionState,
    ): BrowserSessionState = if (generationId == expected) transform() else this

    private fun BrowserSessionState.removeRequest(
        generationId: ServerGenerationId,
        requestId: PairingRequestId,
    ): BrowserSessionState = ifGeneration(generationId) {
        if (pendingRequests.none { it.id == requestId }) {
            this
        } else {
            evolve(pendingRequests = pendingRequests.filterNot { it.id == requestId })
        }
    }
}
