package ru.hznik.devicebridge.data.session

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Clock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import ru.hznik.devicebridge.data.server.MonotonicClock
import ru.hznik.devicebridge.data.session.security.SessionSecretGenerator
import ru.hznik.devicebridge.data.session.security.SessionTokenCredential
import ru.hznik.devicebridge.domain.repository.BrowserSessionRepository
import ru.hznik.devicebridge.domain.repository.TrustedBrowserRepository
import ru.hznik.devicebridge.domain.session.BrowserSession
import ru.hznik.devicebridge.domain.session.BrowserSessionEvent
import ru.hznik.devicebridge.domain.session.BrowserSessionId
import ru.hznik.devicebridge.domain.session.BrowserSessionReducer
import ru.hznik.devicebridge.domain.session.BrowserSessionState
import ru.hznik.devicebridge.domain.session.PairingChallengeId
import ru.hznik.devicebridge.domain.session.PairingRequestId
import ru.hznik.devicebridge.domain.session.PendingBrowserRequest
import ru.hznik.devicebridge.domain.session.ServerGenerationId
import ru.hznik.devicebridge.domain.trust.IssuedTrustedBrowser
import ru.hznik.devicebridge.domain.trust.TRUSTED_BROWSER_MAX_LIFETIME_MILLIS
import ru.hznik.devicebridge.domain.trust.TrustedBrowserAuthenticationResult
import ru.hznik.devicebridge.domain.trust.TrustedBrowserIssueRequest
import ru.hznik.devicebridge.domain.trust.TrustedBrowserId

class SessionGenerationHandle internal constructor(
    internal val generationId: ServerGenerationId,
)

fun interface SessionConnection {
    suspend fun close()
}

sealed interface ChallengeCreationResult {
    data class Created(
        val challengeId: PairingChallengeId,
        val expiresAtElapsedRealtimeMs: Long,
        val confirmTimeoutSeconds: Int,
        val attemptsRemaining: Int,
    ) : ChallengeCreationResult

    data class RateLimited(val retryAfterMs: Long) : ChallengeCreationResult
    data object InvalidMetadata : ChallengeCreationResult
    data object CapacityReached : ChallengeCreationResult
    data object GenerationClosed : ChallengeCreationResult
}

sealed interface SessionConfirmationResult {
    data class Approved(
        val sessionId: BrowserSessionId,
        val token: String,
        val trustedCredential: String? = null,
        val trustedCredentialExpiresAtEpochMillis: Long? = null,
    ) : SessionConfirmationResult

    data class InvalidCode(val remainingAttempts: Int) : SessionConfirmationResult
    data class RateLimited(val retryAfterMs: Long) : SessionConfirmationResult
    data object InvalidChallenge : SessionConfirmationResult
    data object InvalidMetadata : SessionConfirmationResult
    data object Expired : SessionConfirmationResult
    data object Denied : SessionConfirmationResult
    data object TimedOut : SessionConfirmationResult
    data object CapacityReached : SessionConfirmationResult
    data object GenerationClosed : SessionConfirmationResult
}

sealed interface SessionConfirmationRecoveryResult {
    data object Pending : SessionConfirmationRecoveryResult
    data class Approved(
        val confirmation: SessionConfirmationResult.Approved,
    ) : SessionConfirmationRecoveryResult
    data object Denied : SessionConfirmationRecoveryResult
    data object Expired : SessionConfirmationRecoveryResult
    data object CapacityReached : SessionConfirmationRecoveryResult
    data object GenerationClosed : SessionConfirmationRecoveryResult
    data object InvalidMetadata : SessionConfirmationRecoveryResult
}

sealed interface TrustedSessionExchangeResult {
    data class Approved(
        val sessionId: BrowserSessionId,
        val token: String,
    ) : TrustedSessionExchangeResult

    data object InvalidCredential : TrustedSessionExchangeResult
    data object Expired : TrustedSessionExchangeResult
    data object InvalidMetadata : TrustedSessionExchangeResult
    data object CapacityReached : TrustedSessionExchangeResult
    data object GenerationClosed : TrustedSessionExchangeResult
}

class BrowserSessionCoordinator(
    private val clock: MonotonicClock,
    private val secretGenerator: SessionSecretGenerator,
    private val scope: CoroutineScope,
    maxChallenges: Int = MAX_ACTIVE_CHALLENGES,
    private val maxPendingRequests: Int = MAX_CONCURRENT_PENDING_REQUESTS,
    private val maxSessions: Int = 16,
    private val confirmWaitTimeoutMs: Long = PENDING_REQUEST_TTL_MS,
    private val trustedBrowserRepository: TrustedBrowserRepository? = null,
    private val wallClock: Clock = Clock.systemUTC(),
) : BrowserSessionRepository {
    private data class ChallengeRecord(
        val generationId: ServerGenerationId,
        val metadata: NormalizedClientMetadata,
        val rememberBrowserRequested: Boolean,
    )

    private data class PendingEntry(
        val request: PendingBrowserRequest,
        val decision: CompletableDeferred<SessionConfirmationResult>,
    )

    private data class StoredSession(
        val session: BrowserSession,
        val credential: SessionTokenCredential,
    )

    private sealed interface ConfirmationPreparation {
        data class Await(val entry: PendingEntry) : ConfirmationPreparation
        data class Immediate(val result: SessionConfirmationResult) : ConfirmationPreparation
    }

    private sealed interface RecoveryPreparation {
        data class Await(val entry: PendingEntry) : RecoveryPreparation
        data class Immediate(val result: SessionConfirmationRecoveryResult) : RecoveryPreparation
    }

    private data class ApprovalPreparation(
        val entry: PendingEntry,
        val handle: SessionGenerationHandle,
    )

    private val mutex = Mutex()
    private val lifetimePolicy = SessionLifetimePolicy(clock, secretGenerator)
    private val rateLimiter = PairingRateLimiter(clock)
    private val challenges = BoundedExpiringRegistry<PairingChallengeId, ChallengeRecord>(
        clock = clock,
        maxEntries = maxChallenges,
    )
    private val pending = BoundedExpiringRegistry<PairingRequestId, PendingEntry>(
        clock = clock,
        maxEntries = maxPendingRequests,
    )
    private val confirmationRecovery =
        BoundedExpiringRegistry<PairingChallengeId, PendingEntry>(
            clock = clock,
            maxEntries = maxChallenges,
        )
    private val sessions = LinkedHashMap<BrowserSessionId, StoredSession>()
    private val connections = LinkedHashMap<BrowserSessionId, MutableSet<SessionConnection>>()
    private val mutableState = MutableStateFlow(BrowserSessionState.inactive())
    private val mutableActiveConnectionCount = MutableStateFlow(0)
    private val mutableConnectedSessionIds = MutableStateFlow<Set<BrowserSessionId>>(emptySet())

    @Volatile
    private var activeHandle: SessionGenerationHandle? = null
    private var codeRotationJob: Job? = null

    init {
        require(confirmWaitTimeoutMs in 1..PENDING_REQUEST_TTL_MS)
        trustedBrowserRepository?.let { repository ->
            scope.launch {
                repository.trustedBrowsers.collectLatest { browsers ->
                    closeTrustedSessionsMissingFrom(browsers.mapTo(mutableSetOf()) { it.id })
                }
            }
            scope.launch {
                while (true) {
                    delay(TRUSTED_BROWSER_CLEANUP_INTERVAL_MS)
                    runCatching { repository.deleteExpired(wallClock.millis()) }
                }
            }
        }
    }

    override val state: StateFlow<BrowserSessionState> = mutableState.asStateFlow()

    /**
     * Live transport connections across all sessions. Unlike [state] sessions, this drops as soon
     * as a browser tab closes its WebSocket, so idle detection can rely on it.
     */
    val activeConnectionCount: StateFlow<Int> = mutableActiveConnectionCount.asStateFlow()

    override val connectedSessionIds: StateFlow<Set<BrowserSessionId>> =
        mutableConnectedSessionIds.asStateFlow()

    suspend fun activate(generationId: ServerGenerationId): SessionGenerationHandle {
        val (handle, oldConnections) = mutex.withLock {
            val toClose = clearActiveGenerationLocked()
            val newHandle = SessionGenerationHandle(generationId)
            activeHandle = newHandle
            val code = lifetimePolicy.newPairingCode()
            mutableState.value = BrowserSessionReducer.reduce(
                BrowserSessionState.inactive(),
                BrowserSessionEvent.Activated(generationId, code),
            )
            scheduleCodeRotation(newHandle)
            newHandle to toClose
        }
        closeConnections(oldConnections)
        return handle
    }

    fun isCurrent(handle: SessionGenerationHandle): Boolean = activeHandle === handle

    suspend fun closeGeneration(handle: SessionGenerationHandle) {
        val toClose = mutex.withLock {
            if (activeHandle !== handle) emptyList() else clearActiveGenerationLocked()
        }
        closeConnections(toClose)
    }

    suspend fun createChallenge(
        handle: SessionGenerationHandle,
        browserLabel: String,
        sourceIpv4: String,
        rememberBrowserRequested: Boolean = false,
    ): ChallengeCreationResult = mutex.withLock {
        if (activeHandle !== handle) return@withLock ChallengeCreationResult.GenerationClosed
        val metadata = when (val normalized = ClientMetadataNormalizer.normalize(browserLabel, sourceIpv4)) {
            is ClientMetadataResult.Valid -> normalized.metadata
            is ClientMetadataResult.Invalid -> return@withLock ChallengeCreationResult.InvalidMetadata
        }
        when (val limit = rateLimiter.check(metadata.sourceIpv4)) {
            is RateLimitDecision.Blocked -> return@withLock ChallengeCreationResult.RateLimited(
                limit.retryAfterMs,
            )
            is RateLimitDecision.Allowed -> {
                clearExpiredBlock(handle)
                val currentCode = refreshPairingCode(handle)
                val challengeId = PairingChallengeId(secretGenerator.newOpaqueId())
                val result = challenges.put(
                    key = challengeId,
                    value = ChallengeRecord(
                        generationId = handle.generationId,
                        metadata = metadata,
                        rememberBrowserRequested = rememberBrowserRequested,
                    ),
                    expiresAtMs = currentCode.expiresAtElapsedRealtimeMs,
                )
                if (result == RegistryPutResult.CAPACITY_REACHED) {
                    return@withLock ChallengeCreationResult.CapacityReached
                }
                ChallengeCreationResult.Created(
                    challengeId = challengeId,
                    expiresAtElapsedRealtimeMs = currentCode.expiresAtElapsedRealtimeMs,
                    confirmTimeoutSeconds = (PENDING_REQUEST_TTL_MS / 1_000).toInt(),
                    attemptsRemaining = limit.remainingAttempts,
                )
            }
        }
    }

    suspend fun confirmAndAwait(
        handle: SessionGenerationHandle,
        challengeId: PairingChallengeId,
        code: String,
        browserLabel: String,
        sourceIpv4: String,
    ): SessionConfirmationResult {
        val preparation = mutex.withLock {
            prepareConfirmation(handle, challengeId, code, browserLabel, sourceIpv4)
        }
        if (preparation is ConfirmationPreparation.Immediate) return preparation.result
        val entry = (preparation as ConfirmationPreparation.Await).entry
        val decided = withTimeoutOrNull(confirmWaitTimeoutMs) { entry.decision.await() }
        if (decided != null) return decided

        mutex.withLock {
            val removed = pending.remove(entry.request.id)
            if (removed === entry && activeHandle?.generationId == entry.request.generationId) {
                entry.decision.complete(SessionConfirmationResult.TimedOut)
                mutableState.value = BrowserSessionReducer.reduce(
                    mutableState.value,
                    BrowserSessionEvent.RequestExpired(
                        entry.request.generationId,
                        entry.request.id,
                    ),
                )
            }
        }
        return SessionConfirmationResult.TimedOut
    }

    suspend fun recoverConfirmation(
        handle: SessionGenerationHandle,
        challengeId: PairingChallengeId,
        browserLabel: String,
        sourceIpv4: String,
    ): SessionConfirmationRecoveryResult {
        val preparation = mutex.withLock<RecoveryPreparation> {
            if (activeHandle !== handle) {
                return@withLock RecoveryPreparation.Immediate(
                    SessionConfirmationRecoveryResult.GenerationClosed,
                )
            }
            val entry = confirmationRecovery.get(challengeId)
                ?: return@withLock RecoveryPreparation.Immediate(
                    SessionConfirmationRecoveryResult.Expired,
                )
            val metadata = when (
                val normalized = ClientMetadataNormalizer.normalize(browserLabel, sourceIpv4)
            ) {
                is ClientMetadataResult.Valid -> normalized.metadata
                is ClientMetadataResult.Invalid -> {
                    return@withLock RecoveryPreparation.Immediate(
                        SessionConfirmationRecoveryResult.InvalidMetadata,
                    )
                }
            }
            if (
                metadata.browserLabel != entry.request.browserLabel ||
                metadata.sourceIpv4 != entry.request.sourceIpv4
            ) {
                return@withLock RecoveryPreparation.Immediate(
                    SessionConfirmationRecoveryResult.InvalidMetadata,
                )
            }
            if (!entry.decision.isCompleted) {
                RecoveryPreparation.Immediate(SessionConfirmationRecoveryResult.Pending)
            } else {
                RecoveryPreparation.Await(entry)
            }
        }
        if (preparation is RecoveryPreparation.Immediate) return preparation.result
        return when (val result = (preparation as RecoveryPreparation.Await).entry.decision.await()) {
            is SessionConfirmationResult.Approved ->
                SessionConfirmationRecoveryResult.Approved(result)
            SessionConfirmationResult.Denied -> SessionConfirmationRecoveryResult.Denied
            SessionConfirmationResult.CapacityReached ->
                SessionConfirmationRecoveryResult.CapacityReached
            SessionConfirmationResult.GenerationClosed ->
                SessionConfirmationRecoveryResult.GenerationClosed
            else -> SessionConfirmationRecoveryResult.Expired
        }
    }

    override suspend fun approve(requestId: PairingRequestId) {
        approveInternal(requestId, rememberBrowser = false)
    }

    override suspend fun approveAndRemember(requestId: PairingRequestId) {
        approveInternal(requestId, rememberBrowser = true)
    }

    private suspend fun approveInternal(
        requestId: PairingRequestId,
        rememberBrowser: Boolean,
    ) {
        val preparation = mutex.withLock<ApprovalPreparation?> {
            val entry = pending.remove(requestId) ?: return@withLock null
            val handle = activeHandle
            if (handle == null || handle.generationId != entry.request.generationId) {
                entry.decision.complete(SessionConfirmationResult.GenerationClosed)
                return@withLock null
            }
            if (sessions.size >= maxSessions) {
                entry.decision.complete(SessionConfirmationResult.CapacityReached)
                mutableState.value = BrowserSessionReducer.reduce(
                    mutableState.value,
                    BrowserSessionEvent.RequestDenied(entry.request.generationId, requestId),
                )
                return@withLock null
            }
            ApprovalPreparation(entry, handle)
        } ?: return
        val issuedTrustedBrowser = if (
            rememberBrowser && preparation.entry.request.rememberBrowserRequested
        ) {
            issueTrustedBrowser(preparation.entry.request)
        } else {
            null
        }
        var orphanedTrustedBrowser: IssuedTrustedBrowser? = null
        mutex.withLock {
            val entry = preparation.entry
            val handle = activeHandle
            if (
                handle == null ||
                handle !== preparation.handle ||
                handle.generationId != entry.request.generationId
            ) {
                orphanedTrustedBrowser = issuedTrustedBrowser
                entry.decision.complete(SessionConfirmationResult.GenerationClosed)
                return@withLock
            }
            if (sessions.size >= maxSessions) {
                orphanedTrustedBrowser = issuedTrustedBrowser
                entry.decision.complete(SessionConfirmationResult.CapacityReached)
                mutableState.value = BrowserSessionReducer.reduce(
                    mutableState.value,
                    BrowserSessionEvent.RequestDenied(entry.request.generationId, requestId),
                )
                return@withLock
            }
            val rawToken = secretGenerator.newSessionToken()
            val session = BrowserSession(
                id = BrowserSessionId(secretGenerator.newOpaqueId()),
                generationId = handle.generationId,
                browserLabel = entry.request.browserLabel,
                sourceIpv4 = entry.request.sourceIpv4,
                connectedAtElapsedRealtimeMs = clock.nowMs(),
                trustedBrowserId = issuedTrustedBrowser?.browser?.id,
            )
            sessions[session.id] = StoredSession(
                session = session,
                credential = SessionTokenCredential.fromRaw(handle.generationId, rawToken),
            )
            val nextCode = lifetimePolicy.newPairingCode()
            mutableState.value = BrowserSessionReducer.reduce(
                mutableState.value,
                BrowserSessionEvent.RequestApproved(
                    generationId = handle.generationId,
                    requestId = requestId,
                    session = session,
                    nextPairingCode = nextCode,
                ),
            )
            scheduleCodeRotation(handle)
            entry.decision.complete(
                SessionConfirmationResult.Approved(
                    sessionId = session.id,
                    token = rawToken,
                    trustedCredential = issuedTrustedBrowser?.rawCredential,
                    trustedCredentialExpiresAtEpochMillis =
                        issuedTrustedBrowser?.browser?.expiresAtEpochMillis,
                ),
            )
        }
        orphanedTrustedBrowser?.let { issued ->
            runCatching { trustedBrowserRepository?.revoke(issued.browser.id) }
        }
    }

    private suspend fun issueTrustedBrowser(
        request: PendingBrowserRequest,
    ): IssuedTrustedBrowser? {
        val repository = trustedBrowserRepository ?: return null
        val issuedAt = wallClock.millis()
        val expiresAt = Math.addExact(issuedAt, TRUSTED_BROWSER_MAX_LIFETIME_MILLIS)
        return runCatching {
            repository.issue(
                TrustedBrowserIssueRequest(
                    browserLabel = request.browserLabel,
                    issuedAtEpochMillis = issuedAt,
                    expiresAtEpochMillis = expiresAt,
                ),
            )
        }.getOrNull()
    }

    override suspend fun deny(requestId: PairingRequestId) {
        mutex.withLock {
            val entry = pending.remove(requestId) ?: return@withLock
            mutableState.value = BrowserSessionReducer.reduce(
                mutableState.value,
                BrowserSessionEvent.RequestDenied(entry.request.generationId, requestId),
            )
            entry.decision.complete(SessionConfirmationResult.Denied)
        }
    }

    suspend fun authenticate(
        handle: SessionGenerationHandle,
        token: String,
    ): BrowserSession? = mutex.withLock {
        if (activeHandle !== handle || token.isBlank()) return@withLock null
        sessions.values
            .firstOrNull { it.credential.matches(handle.generationId, token) }
            ?.session
    }

    suspend fun exchangeTrusted(
        handle: SessionGenerationHandle,
        rawCredential: String,
        sourceIpv4: String,
        nowEpochMillis: Long = wallClock.millis(),
    ): TrustedSessionExchangeResult {
        if (activeHandle !== handle) return TrustedSessionExchangeResult.GenerationClosed
        val repository = trustedBrowserRepository
            ?: return TrustedSessionExchangeResult.InvalidCredential
        val trustedBrowser = when (
            val authentication = repository.authenticate(rawCredential, nowEpochMillis)
        ) {
            is TrustedBrowserAuthenticationResult.Authenticated -> authentication.browser
            TrustedBrowserAuthenticationResult.Expired -> return TrustedSessionExchangeResult.Expired
            TrustedBrowserAuthenticationResult.Invalid -> {
                return TrustedSessionExchangeResult.InvalidCredential
            }
        }
        return mutex.withLock {
            if (activeHandle !== handle) {
                return@withLock TrustedSessionExchangeResult.GenerationClosed
            }
            val metadata = when (
                val normalized = ClientMetadataNormalizer.normalize(
                    trustedBrowser.browserLabel,
                    sourceIpv4,
                )
            ) {
                is ClientMetadataResult.Valid -> normalized.metadata
                is ClientMetadataResult.Invalid -> {
                    return@withLock TrustedSessionExchangeResult.InvalidMetadata
                }
            }
            if (sessions.size >= maxSessions) {
                return@withLock TrustedSessionExchangeResult.CapacityReached
            }
            val rawToken = secretGenerator.newSessionToken()
            val session = BrowserSession(
                id = BrowserSessionId(secretGenerator.newOpaqueId()),
                generationId = handle.generationId,
                browserLabel = metadata.browserLabel,
                sourceIpv4 = metadata.sourceIpv4,
                connectedAtElapsedRealtimeMs = clock.nowMs(),
                trustedBrowserId = trustedBrowser.id,
            )
            sessions[session.id] = StoredSession(
                session = session,
                credential = SessionTokenCredential.fromRaw(handle.generationId, rawToken),
            )
            mutableState.value = BrowserSessionReducer.reduce(
                mutableState.value,
                BrowserSessionEvent.TrustedSessionConnected(handle.generationId, session),
            )
            TrustedSessionExchangeResult.Approved(session.id, rawToken)
        }
    }

    suspend fun attachConnection(
        sessionId: BrowserSessionId,
        connection: SessionConnection,
    ): Boolean = mutex.withLock {
        if (sessionId !in sessions) return@withLock false
        connections.getOrPut(sessionId, ::linkedSetOf).add(connection)
        publishConnectionCountLocked()
        true
    }

    suspend fun detachConnection(
        sessionId: BrowserSessionId,
        connection: SessionConnection,
    ) {
        mutex.withLock {
            connections[sessionId]?.let { bound ->
                bound.remove(connection)
                if (bound.isEmpty()) connections.remove(sessionId)
            }
            publishConnectionCountLocked()
        }
    }

    override suspend fun revoke(sessionId: BrowserSessionId) {
        val toClose = mutex.withLock {
            val removed = sessions.remove(sessionId) ?: return@withLock emptyList()
            mutableState.value = BrowserSessionReducer.reduce(
                mutableState.value,
                BrowserSessionEvent.SessionRevoked(removed.session.generationId, sessionId),
            )
            connections.remove(sessionId)?.toList().orEmpty()
                .also { publishConnectionCountLocked() }
        }
        toClose.forEach { connection ->
            try {
                connection.close()
            } catch (_: Exception) {
                // Revocation is complete even if a transport was already closed.
            }
        }
    }

    override suspend fun revokeTrustedBrowser(browserId: TrustedBrowserId): Boolean {
        val revoked = trustedBrowserRepository?.revoke(browserId) ?: false
        closeTrustedSessions(setOf(browserId))
        return revoked
    }

    override suspend fun revokeAllTrustedBrowsers(): Int {
        val revoked = trustedBrowserRepository?.revokeAll() ?: 0
        closeAllTrustedSessions()
        return revoked
    }

    private suspend fun closeTrustedSessions(browserIds: Set<TrustedBrowserId>) {
        closeTrustedSessionsMatching { trustedId -> trustedId in browserIds }
    }

    private suspend fun closeTrustedSessionsMissingFrom(
        activeBrowserIds: Set<TrustedBrowserId>,
    ) {
        closeTrustedSessionsMatching { trustedId -> trustedId !in activeBrowserIds }
    }

    private suspend fun closeAllTrustedSessions() {
        closeTrustedSessionsMatching { true }
    }

    private suspend fun closeTrustedSessionsMatching(
        shouldClose: (TrustedBrowserId) -> Boolean,
    ) {
        val toClose = mutex.withLock {
            val sessionIds = sessions.values
                .mapNotNull { stored ->
                    stored.session.trustedBrowserId
                        ?.takeIf(shouldClose)
                        ?.let { stored.session.id }
                }
            sessionIds.flatMap { sessionId ->
                val removed = sessions.remove(sessionId) ?: return@flatMap emptyList()
                mutableState.value = BrowserSessionReducer.reduce(
                    mutableState.value,
                    BrowserSessionEvent.SessionRevoked(
                        removed.session.generationId,
                        sessionId,
                    ),
                )
                connections.remove(sessionId)?.toList().orEmpty()
                    .also { publishConnectionCountLocked() }
            }
        }
        closeConnections(toClose.distinct())
    }

    private fun scheduleCodeRotation(handle: SessionGenerationHandle) {
        codeRotationJob = scope.launch {
            val currentCode = mutableState.value.pairingCode ?: return@launch
            val delayMs = (currentCode.expiresAtElapsedRealtimeMs - clock.nowMs()).coerceAtLeast(1)
            delay(delayMs)
            mutex.withLock {
                if (activeHandle !== handle) return@withLock
                val latestCode = mutableState.value.pairingCode ?: return@withLock
                val rotated = lifetimePolicy.currentOrRotated(latestCode)
                mutableState.value = BrowserSessionReducer.reduce(
                    mutableState.value,
                    BrowserSessionEvent.PairingCodeRotated(handle.generationId, rotated),
                )
                scheduleCodeRotation(handle)
            }
        }
    }

    private fun refreshPairingCode(handle: SessionGenerationHandle) =
        requireNotNull(mutableState.value.pairingCode).let { current ->
            val refreshed = lifetimePolicy.currentOrRotated(current)
            if (refreshed !== current) {
                mutableState.value = BrowserSessionReducer.reduce(
                    mutableState.value,
                    BrowserSessionEvent.PairingCodeRotated(handle.generationId, refreshed),
                )
                scheduleCodeRotation(handle)
            }
            refreshed
        }

    private fun prepareConfirmation(
        handle: SessionGenerationHandle,
        challengeId: PairingChallengeId,
        code: String,
        browserLabel: String,
        sourceIpv4: String,
    ): ConfirmationPreparation {
        if (activeHandle !== handle) {
            return ConfirmationPreparation.Immediate(SessionConfirmationResult.GenerationClosed)
        }
        val currentCode = requireNotNull(mutableState.value.pairingCode)
        if (!lifetimePolicy.isPairingCodeActive(currentCode)) {
            refreshPairingCode(handle)
            return ConfirmationPreparation.Immediate(SessionConfirmationResult.Expired)
        }
        val challenge = challenges.get(challengeId)
            ?: return ConfirmationPreparation.Immediate(SessionConfirmationResult.InvalidChallenge)
        if (challenge.generationId != handle.generationId) {
            return ConfirmationPreparation.Immediate(SessionConfirmationResult.GenerationClosed)
        }
        val metadata = when (val normalized = ClientMetadataNormalizer.normalize(browserLabel, sourceIpv4)) {
            is ClientMetadataResult.Valid -> normalized.metadata
            is ClientMetadataResult.Invalid -> {
                return ConfirmationPreparation.Immediate(SessionConfirmationResult.InvalidMetadata)
            }
        }
        if (metadata != challenge.metadata) {
            return ConfirmationPreparation.Immediate(SessionConfirmationResult.InvalidMetadata)
        }
        when (val limit = rateLimiter.check(metadata.sourceIpv4)) {
            is RateLimitDecision.Blocked -> {
                return ConfirmationPreparation.Immediate(
                    SessionConfirmationResult.RateLimited(limit.retryAfterMs),
                )
            }
            is RateLimitDecision.Allowed -> clearExpiredBlock(handle)
        }
        if (!constantTimeCodeEquals(currentCode.value, code)) {
            return when (val failure = rateLimiter.recordFailure(metadata.sourceIpv4)) {
                is RateLimitDecision.Allowed -> ConfirmationPreparation.Immediate(
                    SessionConfirmationResult.InvalidCode(failure.remainingAttempts),
                )
                is RateLimitDecision.Blocked -> {
                    mutableState.value = BrowserSessionReducer.reduce(
                        mutableState.value,
                        BrowserSessionEvent.SourceBlocked(
                            handle.generationId,
                            Math.addExact(clock.nowMs(), failure.retryAfterMs),
                        ),
                    )
                    ConfirmationPreparation.Immediate(
                        SessionConfirmationResult.RateLimited(failure.retryAfterMs),
                    )
                }
            }
        }

        val requestId = PairingRequestId(secretGenerator.newOpaqueId())
        val request = PendingBrowserRequest(
            id = requestId,
            challengeId = challengeId,
            generationId = handle.generationId,
            browserLabel = metadata.browserLabel,
            sourceIpv4 = metadata.sourceIpv4,
            createdAtElapsedRealtimeMs = clock.nowMs(),
            expiresAtElapsedRealtimeMs = lifetimePolicy.newPendingExpiry(),
            rememberBrowserRequested = challenge.rememberBrowserRequested,
        )
        val entry = PendingEntry(request, CompletableDeferred())
        if (pending.put(requestId, entry, request.expiresAtElapsedRealtimeMs) == RegistryPutResult.CAPACITY_REACHED) {
            return ConfirmationPreparation.Immediate(SessionConfirmationResult.CapacityReached)
        }
        val recoveryExpiry = Math.addExact(
            request.expiresAtElapsedRealtimeMs,
            CONFIRMATION_RECOVERY_RETENTION_MS,
        )
        if (
            confirmationRecovery.put(challengeId, entry, recoveryExpiry) ==
            RegistryPutResult.CAPACITY_REACHED
        ) {
            pending.remove(requestId)
            return ConfirmationPreparation.Immediate(SessionConfirmationResult.CapacityReached)
        }
        challenges.remove(challengeId)
        rateLimiter.recordSuccess(metadata.sourceIpv4)
        mutableState.value = BrowserSessionReducer.reduce(
            mutableState.value,
            BrowserSessionEvent.RequestAdded(request),
        )
        return ConfirmationPreparation.Await(entry)
    }

    private fun constantTimeCodeEquals(expected: String, actual: String): Boolean = MessageDigest.isEqual(
        expected.toByteArray(StandardCharsets.US_ASCII),
        actual.toByteArray(StandardCharsets.US_ASCII),
    )

    private fun clearExpiredBlock(handle: SessionGenerationHandle) {
        val blockedUntil = mutableState.value.blockedUntilElapsedRealtimeMs ?: return
        if (clock.nowMs() >= blockedUntil) {
            mutableState.value = BrowserSessionReducer.reduce(
                mutableState.value,
                BrowserSessionEvent.SourceBlockCleared(handle.generationId),
            )
        }
    }

    private fun clearActiveGenerationLocked(): List<SessionConnection> {
        val handle = activeHandle ?: return emptyList()
        codeRotationJob?.cancel()
        codeRotationJob = null
        challenges.clear()
        pending.clear().forEach { entry ->
            entry.decision.complete(SessionConfirmationResult.GenerationClosed)
        }
        confirmationRecovery.clear()
        sessions.clear()
        rateLimiter.clear()
        val toClose = connections.values.flatten().distinct()
        connections.clear()
        publishConnectionCountLocked()
        activeHandle = null
        mutableState.value = BrowserSessionReducer.reduce(
            mutableState.value,
            BrowserSessionEvent.Deactivated(handle.generationId),
        )
        return toClose
    }

    private fun publishConnectionCountLocked() {
        mutableActiveConnectionCount.value = connections.values.sumOf { it.size }
        mutableConnectedSessionIds.value = connections.keys.toSet()
    }

    private suspend fun closeConnections(connections: List<SessionConnection>) {
        connections.forEach { connection ->
            try {
                connection.close()
            } catch (_: Exception) {
                // Lifecycle cleanup remains complete if transport close races its peer.
            }
        }
    }

    private companion object {
        const val TRUSTED_BROWSER_CLEANUP_INTERVAL_MS = 60_000L
        const val CONFIRMATION_RECOVERY_RETENTION_MS = 5 * 60_000L
    }
}
