package ru.hznik.devicebridge.data.file

import java.security.SecureRandom
import java.util.Base64
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ru.hznik.devicebridge.domain.file.FileTransferId
import ru.hznik.devicebridge.domain.session.BrowserSessionId
import ru.hznik.devicebridge.domain.session.ServerGenerationId

fun interface DownloadGrantTokenSource {
    fun nextBytes(destination: ByteArray)
}

class SecureDownloadGrantTokenSource(
    private val secureRandom: SecureRandom = SecureRandom(),
) : DownloadGrantTokenSource {
    override fun nextBytes(destination: ByteArray) {
        secureRandom.nextBytes(destination)
    }
}

data class DownloadGrantScope(
    val generationId: ServerGenerationId,
    val sessionId: BrowserSessionId,
    val transferId: FileTransferId,
)

data class IssuedDownloadGrant(
    val token: String,
    val expiresAtEpochMillis: Long,
    val downloadPath: String,
)

class DownloadGrantRegistry(
    private val nowEpochMillis: () -> Long,
    private val tokenSource: DownloadGrantTokenSource = SecureDownloadGrantTokenSource(),
    private val ttlMillis: Long = DEFAULT_TTL_MILLIS,
) {
    private data class StoredGrant(
        val scope: DownloadGrantScope,
        val expiresAtEpochMillis: Long,
    )

    private val mutex = Mutex()
    private val grants = LinkedHashMap<String, StoredGrant>()
    /** Grants already used by a download; the browser repeats the same URL to resume it. */
    private val consumed = LinkedHashMap<String, DownloadGrantScope>()

    init {
        require(ttlMillis > 0) { "Download grant TTL must be positive" }
    }

    suspend fun issue(
        generationId: ServerGenerationId,
        sessionId: BrowserSessionId,
        transferId: FileTransferId,
    ): IssuedDownloadGrant = mutex.withLock {
        val now = nowEpochMillis()
        pruneExpired(now)
        val token = nextUniqueToken()
        val expiresAt = Math.addExact(now, ttlMillis)
        grants[token] = StoredGrant(
            scope = DownloadGrantScope(generationId, sessionId, transferId),
            expiresAtEpochMillis = expiresAt,
        )
        IssuedDownloadGrant(
            token = token,
            expiresAtEpochMillis = expiresAt,
            downloadPath = "/api/v1/files/" + transferId.value + "?grant=" + token,
        )
    }

    suspend fun consume(
        token: String,
        generationId: ServerGenerationId,
        sessionId: BrowserSessionId,
        transferId: FileTransferId,
    ): DownloadGrantScope? = mutex.withLock {
        val now = nowEpochMillis()
        pruneExpired(now)
        val expected = DownloadGrantScope(generationId, sessionId, transferId)
        val stored = grants[token] ?: return@withLock null
        if (stored.scope != expected) return@withLock null
        grants.remove(token)
        consumed[token] = stored.scope
        stored.scope
    }

    suspend fun consume(
        token: String,
        generationId: ServerGenerationId,
        transferId: FileTransferId,
    ): DownloadGrantScope? = mutex.withLock {
        val now = nowEpochMillis()
        pruneExpired(now)
        val stored = grants[token] ?: return@withLock null
        if (
            stored.scope.generationId != generationId ||
            stored.scope.transferId != transferId
        ) {
            return@withLock null
        }
        grants.remove(token)
        consumed[token] = stored.scope
        stored.scope
    }

    /** Scope of an already used grant of [transferId], for a ranged continuation. */
    suspend fun resumeScope(
        token: String,
        generationId: ServerGenerationId,
        transferId: FileTransferId,
    ): DownloadGrantScope? = mutex.withLock {
        consumed[token]?.takeIf { scope ->
            scope.generationId == generationId && scope.transferId == transferId
        }
    }

    /** Used grants of [keepResumable] transfers still allow continuing those downloads. */
    suspend fun invalidateSession(
        generationId: ServerGenerationId,
        sessionId: BrowserSessionId,
        keepResumable: Set<FileTransferId> = emptySet(),
    ) = mutex.withLock {
        grants.entries.removeAll { (_, grant) ->
            grant.scope.generationId == generationId &&
                grant.scope.sessionId == sessionId
        }
        consumed.entries.removeAll { (_, scope) ->
            scope.generationId == generationId &&
                scope.sessionId == sessionId &&
                scope.transferId !in keepResumable
        }
    }

    /** With [keepResumable], a used grant still allows continuing the interrupted download. */
    suspend fun invalidateTransfer(
        generationId: ServerGenerationId,
        transferId: FileTransferId,
        keepResumable: Boolean = false,
    ) = mutex.withLock {
        grants.entries.removeAll { (_, grant) ->
            grant.scope.generationId == generationId &&
                grant.scope.transferId == transferId
        }
        if (!keepResumable) {
            consumed.entries.removeAll { (_, scope) ->
                scope.generationId == generationId && scope.transferId == transferId
            }
        }
    }

    suspend fun invalidateGeneration(generationId: ServerGenerationId) = mutex.withLock {
        grants.entries.removeAll { (_, grant) -> grant.scope.generationId == generationId }
        consumed.entries.removeAll { (_, scope) -> scope.generationId == generationId }
    }

    private fun nextUniqueToken(): String {
        repeat(MAX_COLLISION_ATTEMPTS) {
            val randomBytes = ByteArray(GRANT_BYTES)
            tokenSource.nextBytes(randomBytes)
            val token = Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes)
            if (token !in grants) return token
        }
        error("Unable to allocate a unique download grant")
    }

    private fun pruneExpired(nowEpochMillis: Long) {
        grants.entries.removeAll { (_, grant) ->
            nowEpochMillis >= grant.expiresAtEpochMillis
        }
    }

    private companion object {
        const val DEFAULT_TTL_MILLIS = 30_000L
        const val GRANT_BYTES = 16
        const val MAX_COLLISION_ATTEMPTS = 16
    }
}
