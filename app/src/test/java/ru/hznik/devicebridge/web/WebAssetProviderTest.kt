package ru.hznik.devicebridge.web

import java.io.ByteArrayInputStream
import java.io.InputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WebAssetProviderTest {

    private val files = mapOf(
        "index.html" to "<!doctype html>".encodeToByteArray(),
        "web-manifest.json" to """{"protocolVersion":1}""".encodeToByteArray(),
        "assets/index-AbCd1234.js" to "export{}".encodeToByteArray(),
        "assets/index-EfGh5678.css" to ":root{}".encodeToByteArray(),
    )
    private val source = FakeWebAssetSource(files)
    private val provider = AllowlistedWebAssetProvider(files.keys, source)

    @Test
    fun rootResolvesToAllowlistedIndexWithExactMetadata() {
        val asset = requireNotNull(provider.find("/"))

        assertEquals("index.html", asset.path)
        assertEquals("text/html; charset=utf-8", asset.contentType)
        assertEquals(files.getValue("index.html").size.toLong(), asset.length)
        assertArrayEquals(files.getValue("index.html"), asset.openStream().use(InputStream::readBytes))
    }

    @Test
    fun allowlistedAssetsReceiveKnownMimeTypes() {
        assertEquals(
            "application/json; charset=utf-8",
            provider.find("/web-manifest.json")?.contentType,
        )
        assertEquals(
            "text/javascript; charset=utf-8",
            provider.find("/assets/index-AbCd1234.js")?.contentType,
        )
        assertEquals(
            "text/css; charset=utf-8",
            provider.find("/assets/index-EfGh5678.css")?.contentType,
        )
    }

    @Test
    fun missingOrUnallowlistedPathReturnsNullWithoutOpeningSource() {
        assertNull(provider.find("/assets/missing.js"))
        assertNull(provider.find("/favicon.ico"))

        assertTrue(source.requestedPaths.isEmpty())
    }

    @Test
    fun missingAllowlistedSourceEntryReturnsNull() {
        val missingSource = FakeWebAssetSource(emptyMap())
        val missingProvider = AllowlistedWebAssetProvider(setOf("index.html"), missingSource)

        assertNull(missingProvider.find("/"))
        assertEquals(listOf("index.html"), missingSource.requestedPaths)
    }

    @Test
    fun traversalAndAmbiguousPathsAreRejectedBeforeSourceAccess() {
        val attacks = listOf(
            "../index.html",
            "/../index.html",
            "/assets/../index.html",
            "/assets//index-AbCd1234.js",
            "//assets/index-AbCd1234.js",
            "/assets\\index-AbCd1234.js",
            "/%2e%2e/index.html",
            "/assets/%2E%2E/index.html",
            "/assets/index-AbCd1234.js?debug=true",
            "/assets/index-AbCd1234.js#fragment",
            "/assets/\u0000index.js",
        )

        attacks.forEach { path -> assertNull(path, provider.find(path)) }
        assertTrue(source.requestedPaths.isEmpty())
    }
}

private class FakeWebAssetSource(
    private val files: Map<String, ByteArray>,
) : WebAssetSource {
    val requestedPaths = mutableListOf<String>()

    override fun describe(path: String): WebAssetDescriptor? {
        requestedPaths += path
        val bytes = files[path] ?: return null
        return WebAssetDescriptor(
            length = bytes.size.toLong(),
            openStream = { ByteArrayInputStream(bytes) },
        )
    }
}
