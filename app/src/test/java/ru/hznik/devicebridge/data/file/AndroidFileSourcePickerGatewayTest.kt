package ru.hznik.devicebridge.data.file

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidFileSourcePickerGatewayTest {

    @Test
    fun resolvesMultipleUrisInSelectionOrder() {
        val gateway = AndroidFileSourcePickerGateway(
            metadataSource = FakeMetadataSource(
                "content://docs/one" to AndroidDocumentMetadata(
                    displayName = "one.txt",
                    sizeBytes = 12,
                    mimeType = "text/plain",
                ),
                "content://docs/two" to AndroidDocumentMetadata(
                    displayName = "two.bin",
                    sizeBytes = 34,
                    mimeType = "application/octet-stream",
                ),
            ),
        )

        val result = gateway.inspect(
            listOf("content://docs/one", "content://docs/two"),
        )

        assertEquals(listOf("one.txt", "two.bin"), result.map { it.source?.displayName })
        assertEquals(listOf(12L, 34L), result.map { it.source?.sizeBytes })
        assertEquals(listOf("text/plain", "application/octet-stream"), result.map { it.source?.mimeType })
    }

    @Test
    fun usesSafeFallbacksAndKeepsUnavailableProviderAsItemError() {
        val gateway = AndroidFileSourcePickerGateway(
            metadataSource = FakeMetadataSource(
                "content://docs/fallback" to AndroidDocumentMetadata(
                    displayName = " ",
                    sizeBytes = 0,
                    mimeType = null,
                ),
                "content://docs/missing" to null,
            ),
        )

        val result = gateway.inspect(
            listOf("content://docs/fallback", "content://docs/missing"),
        )

        assertEquals("document", result[0].source?.displayName)
        assertEquals("application/octet-stream", result[0].source?.mimeType)
        assertTrue(result[1].source == null)
        assertEquals(AndroidFileSourceError.UNAVAILABLE_PROVIDER, result[1].error)
    }

    private class FakeMetadataSource(
        vararg values: Pair<String, AndroidDocumentMetadata?>,
    ) : AndroidDocumentMetadataSource {
        private val metadata = values.toMap()

        override fun read(uri: String): AndroidDocumentMetadata? = metadata[uri]
    }
}
