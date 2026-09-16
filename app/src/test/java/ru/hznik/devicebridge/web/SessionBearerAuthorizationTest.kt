package ru.hznik.devicebridge.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SessionBearerAuthorizationTest {

    @Test
    fun acceptsExactlyOneCanonicalBearerValue() {
        assertEquals(
            "Abc_123-def",
            SessionBearerAuthorization.parse(listOf("Bearer Abc_123-def")),
        )
    }

    @Test
    fun rejectsMissingMalformedDuplicateAndOversizedValues() {
        assertNull(SessionBearerAuthorization.parse(emptyList()))
        assertNull(SessionBearerAuthorization.parse(listOf("Basic abc")))
        assertNull(SessionBearerAuthorization.parse(listOf("bearer abc")))
        assertNull(SessionBearerAuthorization.parse(listOf("Bearer ")))
        assertNull(SessionBearerAuthorization.parse(listOf("Bearer abc def")))
        assertNull(
            SessionBearerAuthorization.parse(
                listOf("Bearer first", "Bearer second"),
            ),
        )
        assertNull(SessionBearerAuthorization.parse(listOf("Bearer " + "x".repeat(257))))
    }
}
