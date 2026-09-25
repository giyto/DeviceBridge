package ru.hznik.devicebridge.data.session

import ru.hznik.devicebridge.core.protocol.session.MAX_CLIENT_LABEL_LENGTH
import ru.hznik.devicebridge.domain.session.isCanonicalIpv4

data class NormalizedClientMetadata(
    val browserLabel: String,
    val sourceIpv4: String,
)

enum class ClientMetadataError {
    INVALID_LABEL,
    INVALID_SOURCE_IPV4,
}

sealed interface ClientMetadataResult {
    data class Valid(val metadata: NormalizedClientMetadata) : ClientMetadataResult
    data class Invalid(val error: ClientMetadataError) : ClientMetadataResult
}

object ClientMetadataNormalizer {
    fun normalize(browserLabel: String, sourceIpv4: String): ClientMetadataResult {
        val label = browserLabel.trim()
        if (
            label.isEmpty() ||
            label.length > MAX_CLIENT_LABEL_LENGTH ||
            label.any(Char::isISOControl)
        ) {
            return ClientMetadataResult.Invalid(ClientMetadataError.INVALID_LABEL)
        }
        if (!isCanonicalIpv4(sourceIpv4)) {
            return ClientMetadataResult.Invalid(ClientMetadataError.INVALID_SOURCE_IPV4)
        }
        return ClientMetadataResult.Valid(NormalizedClientMetadata(label, sourceIpv4))
    }

    fun normalizeOrNull(browserLabel: String, sourceIpv4: String): NormalizedClientMetadata? =
        (normalize(browserLabel, sourceIpv4) as? ClientMetadataResult.Valid)?.metadata
}
