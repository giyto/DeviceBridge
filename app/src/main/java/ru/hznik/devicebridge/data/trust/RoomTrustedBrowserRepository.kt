package ru.hznik.devicebridge.data.trust

import java.time.Clock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import ru.hznik.devicebridge.data.persistence.room.TrustedBrowserDao
import ru.hznik.devicebridge.data.persistence.room.toDomainOrNull
import ru.hznik.devicebridge.data.persistence.room.toEntity
import ru.hznik.devicebridge.domain.repository.TrustedBrowserRepository
import ru.hznik.devicebridge.domain.trust.IssuedTrustedBrowser
import ru.hznik.devicebridge.domain.trust.TrustedBrowser
import ru.hznik.devicebridge.domain.trust.TrustedBrowserAuthenticationResult
import ru.hznik.devicebridge.domain.trust.TrustedBrowserId
import ru.hznik.devicebridge.domain.trust.TrustedBrowserIssueRequest

class RoomTrustedBrowserRepository(
    private val dao: TrustedBrowserDao,
    private val credentialGenerator: TrustedCredentialGenerator,
    private val credentialVerifier: TrustedCredentialVerifier,
    private val clock: Clock,
    applicationScope: CoroutineScope,
) : TrustedBrowserRepository {
    override val trustedBrowsers: Flow<List<TrustedBrowser>> =
        dao.observeAll().map { entities ->
            entities.mapNotNull { it.toDomainOrNull() }
        }

    init {
        applicationScope.launch {
            runCatching { dao.deleteExpired(clock.millis()) }
        }
    }

    override suspend fun issue(
        request: TrustedBrowserIssueRequest,
    ): IssuedTrustedBrowser {
        dao.deleteExpired(request.issuedAtEpochMillis)
        val rawCredential = credentialGenerator.generate()
        val browser = TrustedBrowser(
            id = TrustedBrowserId(credentialGenerator.generate()),
            browserLabel = request.browserLabel.trim(),
            createdAtEpochMillis = request.issuedAtEpochMillis,
            lastUsedAtEpochMillis = null,
            expiresAtEpochMillis = request.expiresAtEpochMillis,
        )
        dao.insert(
            browser.toEntity(
                credentialVerifier.verifierFor(rawCredential),
            ),
        )
        return IssuedTrustedBrowser(browser, rawCredential)
    }

    override suspend fun authenticate(
        rawCredential: String,
        nowEpochMillis: Long,
    ): TrustedBrowserAuthenticationResult {
        if (rawCredential.isBlank() || rawCredential.length > MAX_RAW_CREDENTIAL_LENGTH) {
            return TrustedBrowserAuthenticationResult.Invalid
        }
        val matches = dao.getAll().filter { entity ->
            credentialVerifier.matches(rawCredential, entity.credentialVerifier)
        }
        val entity = matches.firstOrNull()
        if (entity == null) {
            dao.deleteExpired(nowEpochMillis)
            return TrustedBrowserAuthenticationResult.Invalid
        }
        if (entity.expiresAtEpochMillis <= nowEpochMillis) {
            dao.deleteById(entity.trustedBrowserId)
            dao.deleteExpired(nowEpochMillis)
            return TrustedBrowserAuthenticationResult.Expired
        }
        dao.updateLastUsed(entity.trustedBrowserId, nowEpochMillis)
        dao.deleteExpired(nowEpochMillis)
        val browser = entity.copy(lastUsedAtEpochMillis = nowEpochMillis).toDomainOrNull()
            ?: return TrustedBrowserAuthenticationResult.Invalid
        return TrustedBrowserAuthenticationResult.Authenticated(browser)
    }

    override suspend fun revoke(browserId: TrustedBrowserId): Boolean =
        dao.deleteById(browserId.value) > 0

    override suspend fun revokeAll(): Int = dao.clear()

    override suspend fun deleteExpired(nowEpochMillis: Long): Int =
        dao.deleteExpired(nowEpochMillis)

    private companion object {
        const val MAX_RAW_CREDENTIAL_LENGTH = 256
    }
}
