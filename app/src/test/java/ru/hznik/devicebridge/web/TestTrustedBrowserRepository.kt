package ru.hznik.devicebridge.web

import kotlinx.coroutines.flow.MutableStateFlow
import ru.hznik.devicebridge.domain.repository.TrustedBrowserRepository
import ru.hznik.devicebridge.domain.trust.IssuedTrustedBrowser
import ru.hznik.devicebridge.domain.trust.TrustedBrowser
import ru.hznik.devicebridge.domain.trust.TrustedBrowserAuthenticationResult
import ru.hznik.devicebridge.domain.trust.TrustedBrowserId
import ru.hznik.devicebridge.domain.trust.TrustedBrowserIssueRequest

internal class TestTrustedBrowserRepository : TrustedBrowserRepository {
    private val mutableBrowsers = MutableStateFlow<List<TrustedBrowser>>(emptyList())
    private val credentials = linkedMapOf<String, TrustedBrowserId>()
    private val forcedExpired = mutableSetOf<String>()
    private var nextId = 1
    override val trustedBrowsers = mutableBrowsers

    override suspend fun issue(request: TrustedBrowserIssueRequest): IssuedTrustedBrowser {
        val id = TrustedBrowserId("trusted-${nextId++}")
        val credential = "trusted-credential-${id.value}"
        val browser = TrustedBrowser(
            id = id,
            browserLabel = request.browserLabel,
            createdAtEpochMillis = request.issuedAtEpochMillis,
            lastUsedAtEpochMillis = null,
            expiresAtEpochMillis = request.expiresAtEpochMillis,
        )
        mutableBrowsers.value = mutableBrowsers.value + browser
        credentials[credential] = id
        return IssuedTrustedBrowser(browser, credential)
    }

    override suspend fun authenticate(
        rawCredential: String,
        nowEpochMillis: Long,
    ): TrustedBrowserAuthenticationResult {
        if (rawCredential in forcedExpired) return TrustedBrowserAuthenticationResult.Expired
        val id = credentials[rawCredential]
            ?: return TrustedBrowserAuthenticationResult.Invalid
        val browser = mutableBrowsers.value.firstOrNull { it.id == id }
            ?: return TrustedBrowserAuthenticationResult.Invalid
        if (browser.expiresAtEpochMillis <= nowEpochMillis) {
            revoke(id)
            return TrustedBrowserAuthenticationResult.Expired
        }
        val updated = browser.copy(lastUsedAtEpochMillis = nowEpochMillis)
        mutableBrowsers.value = mutableBrowsers.value.map { if (it.id == id) updated else it }
        return TrustedBrowserAuthenticationResult.Authenticated(updated)
    }

    fun forceExpired(rawCredential: String) {
        forcedExpired += rawCredential
    }

    override suspend fun revoke(browserId: TrustedBrowserId): Boolean {
        val removed = mutableBrowsers.value.any { it.id == browserId }
        mutableBrowsers.value = mutableBrowsers.value.filterNot { it.id == browserId }
        credentials.entries.removeAll { it.value == browserId }
        return removed
    }

    override suspend fun revokeAll(): Int {
        val count = mutableBrowsers.value.size
        mutableBrowsers.value = emptyList()
        credentials.clear()
        return count
    }

    override suspend fun deleteExpired(nowEpochMillis: Long): Int {
        val ids = mutableBrowsers.value
            .filter { it.expiresAtEpochMillis <= nowEpochMillis }
            .map { it.id }
        ids.forEach { revoke(it) }
        return ids.size
    }
}
