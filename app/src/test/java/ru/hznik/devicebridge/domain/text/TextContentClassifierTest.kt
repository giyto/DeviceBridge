package ru.hznik.devicebridge.domain.text

import org.junit.Assert.assertEquals
import org.junit.Test

class TextContentClassifierTest {

    @Test
    fun classifiesOnlyCompleteHttpAndHttpsUrlsWithHostAsLinks() {
        val links = listOf(
            "https://example.com",
            "http://example.com/path?q=1#part",
            "HTTPS://EXAMPLE.COM/resource",
        )

        links.forEach { value ->
            assertEquals(value, TextContentKind.LINK, TextContentClassifier.classify(value))
        }
    }

    @Test
    fun keepsRelativeMixedAndUnsupportedSchemesAsText() {
        val textValues = listOf(
            "example.com",
            "/relative/path",
            "Открой https://example.com",
            "https://example.com and more",
            "javascript:alert(1)",
            "data:text/html,hello",
            "file:///C:/secret.txt",
            "https://",
            "https:///missing-host",
        )

        textValues.forEach { value ->
            assertEquals(value, TextContentKind.TEXT, TextContentClassifier.classify(value))
        }
    }
}
