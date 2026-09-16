package ru.hznik.devicebridge.data.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.hznik.devicebridge.core.protocol.session.MAX_SESSION_JSON_BYTES

class ClientMetadataNormalizerTest {

    @Test
    fun trimsAllowedLabelAndCanonicalizesIpv4() {
        val result = ClientMetadataNormalizer.normalize("  Edge <Office>  ", "192.168.1.20")

        assertEquals(
            ClientMetadataResult.Valid(
                NormalizedClientMetadata("Edge <Office>", "192.168.1.20"),
            ),
            result,
        )
    }

    @Test
    fun rejectsControlCharactersOversizedLabelsAndNonCanonicalIpv4() {
        assertEquals(
            ClientMetadataResult.Invalid(ClientMetadataError.INVALID_LABEL),
            ClientMetadataNormalizer.normalize("Edge\nInjected", "192.168.1.20"),
        )
        assertEquals(
            ClientMetadataResult.Invalid(ClientMetadataError.INVALID_LABEL),
            ClientMetadataNormalizer.normalize("x".repeat(65), "192.168.1.20"),
        )
        listOf("localhost", "192.168.001.20", "256.1.1.1", "::1").forEach { address ->
            assertEquals(
                ClientMetadataResult.Invalid(ClientMetadataError.INVALID_SOURCE_IPV4),
                ClientMetadataNormalizer.normalize("Edge", address),
            )
        }
    }

    @Test
    fun jsonBodyLimitIsEnforcedInUtf8Bytes() {
        assertTrue(ClientMetadataNormalizer.isJsonBodySizeAccepted(ByteArray(MAX_SESSION_JSON_BYTES)))
        assertEquals(
            false,
            ClientMetadataNormalizer.isJsonBodySizeAccepted(ByteArray(MAX_SESSION_JSON_BYTES + 1)),
        )
    }
}
