package ru.hznik.devicebridge.domain.session

private const val MAX_OPAQUE_ID_LENGTH = 64
private const val MAX_BROWSER_LABEL_LENGTH = 64

@JvmInline
value class ServerGenerationId(val value: Long) {
    init {
        require(value > 0) { "Generation identifier must be positive" }
    }
}

@JvmInline
value class PairingChallengeId(val value: String) {
    init {
        requireValidOpaqueId(value, "Challenge")
    }
}

@JvmInline
value class PairingRequestId(val value: String) {
    init {
        requireValidOpaqueId(value, "Request")
    }
}

@JvmInline
value class BrowserSessionId(val value: String) {
    init {
        requireValidOpaqueId(value, "Session")
    }
}

data class PairingCodeState(
    val value: String,
    val expiresAtElapsedRealtimeMs: Long,
) {
    init {
        require(value.length == 6 && value.all(Char::isDigit)) {
            "Pairing code must contain exactly six digits"
        }
        require(expiresAtElapsedRealtimeMs > 0) { "Pairing code expiry must be positive" }
    }
}

data class PendingBrowserRequest(
    val id: PairingRequestId,
    val challengeId: PairingChallengeId,
    val generationId: ServerGenerationId,
    val browserLabel: String,
    val sourceIpv4: String,
    val createdAtElapsedRealtimeMs: Long,
    val expiresAtElapsedRealtimeMs: Long,
    val rememberBrowserRequested: Boolean = false,
) {
    init {
        requireValidBrowserLabel(browserLabel)
        requireValidIpv4(sourceIpv4)
        require(createdAtElapsedRealtimeMs >= 0) { "Request creation time must not be negative" }
        require(expiresAtElapsedRealtimeMs > createdAtElapsedRealtimeMs) {
            "Request expiry must be after its creation time"
        }
    }
}

data class BrowserSession(
    val id: BrowserSessionId,
    val generationId: ServerGenerationId,
    val browserLabel: String,
    val sourceIpv4: String,
    val connectedAtElapsedRealtimeMs: Long,
    val trustedBrowserId: ru.hznik.devicebridge.domain.trust.TrustedBrowserId? = null,
) {
    init {
        requireValidBrowserLabel(browserLabel)
        requireValidIpv4(sourceIpv4)
        require(connectedAtElapsedRealtimeMs >= 0) { "Connection time must not be negative" }
    }
}

enum class BrowserSessionPhase {
    INACTIVE,
    READY,
    PENDING,
    CONNECTED,
    BLOCKED,
    ERROR,
}

enum class BrowserApprovalDecision {
    ALLOW_ONCE,
    ALLOW_AND_REMEMBER,
    REJECT,
}

sealed interface BrowserSessionError {
    data object CapacityReached : BrowserSessionError
    data object GenerationClosed : BrowserSessionError
    data object RequestExpired : BrowserSessionError
    data object RequestNotFound : BrowserSessionError
    data class Unexpected(val technicalCause: String) : BrowserSessionError {
        init {
            require(technicalCause.isNotBlank())
        }
    }
}

@ConsistentCopyVisibility
data class BrowserSessionState private constructor(
    val generationId: ServerGenerationId?,
    val pairingCode: PairingCodeState?,
    val pendingRequests: List<PendingBrowserRequest>,
    val sessions: List<BrowserSession>,
    val blockedUntilElapsedRealtimeMs: Long?,
    val error: BrowserSessionError?,
) {
    val isActive: Boolean
        get() = generationId != null

    val phase: BrowserSessionPhase
        get() = when {
            generationId == null -> BrowserSessionPhase.INACTIVE
            error != null -> BrowserSessionPhase.ERROR
            blockedUntilElapsedRealtimeMs != null -> BrowserSessionPhase.BLOCKED
            pendingRequests.isNotEmpty() -> BrowserSessionPhase.PENDING
            sessions.isNotEmpty() -> BrowserSessionPhase.CONNECTED
            else -> BrowserSessionPhase.READY
        }

    init {
        if (generationId == null) {
            require(pairingCode == null)
            require(pendingRequests.isEmpty())
            require(sessions.isEmpty())
            require(blockedUntilElapsedRealtimeMs == null)
            require(error == null)
        } else {
            require(pairingCode != null) { "An active generation requires a pairing code" }
            require(pendingRequests.all { it.generationId == generationId }) {
                "Pending requests must belong to the active generation"
            }
            require(sessions.all { it.generationId == generationId }) {
                "Sessions must belong to the active generation"
            }
            require(pendingRequests.map { it.id }.distinct().size == pendingRequests.size)
            require(sessions.map { it.id }.distinct().size == sessions.size)
            require(blockedUntilElapsedRealtimeMs == null || blockedUntilElapsedRealtimeMs > 0)
        }
    }

    companion object {
        fun inactive(): BrowserSessionState = BrowserSessionState(
            generationId = null,
            pairingCode = null,
            pendingRequests = emptyList(),
            sessions = emptyList(),
            blockedUntilElapsedRealtimeMs = null,
            error = null,
        )

        fun active(
            generationId: ServerGenerationId,
            pairingCode: PairingCodeState,
            pendingRequests: List<PendingBrowserRequest> = emptyList(),
            sessions: List<BrowserSession> = emptyList(),
            blockedUntilElapsedRealtimeMs: Long? = null,
            error: BrowserSessionError? = null,
        ): BrowserSessionState = BrowserSessionState(
            generationId = generationId,
            pairingCode = pairingCode,
            pendingRequests = pendingRequests.toList(),
            sessions = sessions.toList(),
            blockedUntilElapsedRealtimeMs = blockedUntilElapsedRealtimeMs,
            error = error,
        )
    }

    internal fun evolve(
        pairingCode: PairingCodeState = requireNotNull(this.pairingCode),
        pendingRequests: List<PendingBrowserRequest> = this.pendingRequests,
        sessions: List<BrowserSession> = this.sessions,
        blockedUntilElapsedRealtimeMs: Long? = this.blockedUntilElapsedRealtimeMs,
        error: BrowserSessionError? = this.error,
    ): BrowserSessionState = active(
        generationId = requireNotNull(generationId),
        pairingCode = pairingCode,
        pendingRequests = pendingRequests,
        sessions = sessions,
        blockedUntilElapsedRealtimeMs = blockedUntilElapsedRealtimeMs,
        error = error,
    )
}

private fun requireValidOpaqueId(value: String, label: String) {
    require(value.isNotBlank()) { "$label identifier must not be blank" }
    require(value.length <= MAX_OPAQUE_ID_LENGTH) { "$label identifier is too long" }
    require(value.none(Char::isISOControl)) { "$label identifier contains control characters" }
}

private fun requireValidBrowserLabel(value: String) {
    require(value.isNotBlank()) { "Browser label must not be blank" }
    require(value.length <= MAX_BROWSER_LABEL_LENGTH) { "Browser label is too long" }
    require(value.none(Char::isISOControl)) { "Browser label contains control characters" }
}

private fun requireValidIpv4(value: String) {
    val octets = value.split('.')
    require(octets.size == 4 && octets.all { octet ->
        octet.isNotEmpty() &&
            octet.length <= 3 &&
            octet.all(Char::isDigit) &&
            (octet.length == 1 || octet.first() != '0') &&
            octet.toInt() in 0..255
    }) { "Source address must be a canonical IPv4 address" }
}
