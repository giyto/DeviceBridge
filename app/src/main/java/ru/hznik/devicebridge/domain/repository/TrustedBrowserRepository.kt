package ru.hznik.devicebridge.domain.repository

import kotlinx.coroutines.flow.Flow
import ru.hznik.devicebridge.domain.trust.IssuedTrustedBrowser
import ru.hznik.devicebridge.domain.trust.TrustedBrowser
import ru.hznik.devicebridge.domain.trust.TrustedBrowserAuthenticationResult
import ru.hznik.devicebridge.domain.trust.TrustedBrowserId
import ru.hznik.devicebridge.domain.trust.TrustedBrowserIssueRequest

interface TrustedBrowserRepository {
    val trustedBrowsers: Flow<List<TrustedBrowser>>

    suspend fun issue(request: TrustedBrowserIssueRequest): IssuedTrustedBrowser

    suspend fun authenticate(
        rawCredential: String,
        nowEpochMillis: Long,
    ): TrustedBrowserAuthenticationResult

    suspend fun revoke(browserId: TrustedBrowserId): Boolean

    suspend fun revokeAll(): Int

    suspend fun deleteExpired(nowEpochMillis: Long): Int
}
