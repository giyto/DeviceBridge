package ru.hznik.devicebridge.domain.trust

private const val MAX_TRUSTED_BROWSER_ID_LENGTH = 96
private const val MAX_TRUSTED_BROWSER_LABEL_LENGTH = 64
const val TRUSTED_BROWSER_MAX_LIFETIME_MILLIS = 30L * 24 * 60 * 60 * 1_000

@JvmInline
value class TrustedBrowserId(val value: String) {
    init {
        require(value.isNotBlank())
        require(value.length <= MAX_TRUSTED_BROWSER_ID_LENGTH)
        require(value.none(Char::isISOControl))
    }
}

data class TrustedBrowser(
    val id: TrustedBrowserId,
    val browserLabel: String,
    val createdAtEpochMillis: Long,
    val lastUsedAtEpochMillis: Long?,
    val expiresAtEpochMillis: Long,
) {
    init {
        require(browserLabel.isNotBlank())
        require(browserLabel.length <= MAX_TRUSTED_BROWSER_LABEL_LENGTH)
        require(browserLabel.none(Char::isISOControl))
        require(createdAtEpochMillis > 0)
        require(lastUsedAtEpochMillis == null || lastUsedAtEpochMillis >= createdAtEpochMillis)
        require(expiresAtEpochMillis > createdAtEpochMillis)
    }
}

data class TrustedBrowserIssueRequest(
    val browserLabel: String,
    val issuedAtEpochMillis: Long,
    val expiresAtEpochMillis: Long,
) {
    init {
        require(browserLabel.isNotBlank())
        require(browserLabel.length <= MAX_TRUSTED_BROWSER_LABEL_LENGTH)
        require(browserLabel.none(Char::isISOControl))
        require(issuedAtEpochMillis > 0)
        require(expiresAtEpochMillis > issuedAtEpochMillis)
        require(
            expiresAtEpochMillis - issuedAtEpochMillis <=
                TRUSTED_BROWSER_MAX_LIFETIME_MILLIS,
        )
    }
}

data class IssuedTrustedBrowser(
    val browser: TrustedBrowser,
    val rawCredential: String,
)

sealed interface TrustedBrowserAuthenticationResult {
    data class Authenticated(val browser: TrustedBrowser) : TrustedBrowserAuthenticationResult
    data object Invalid : TrustedBrowserAuthenticationResult
    data object Expired : TrustedBrowserAuthenticationResult
}
