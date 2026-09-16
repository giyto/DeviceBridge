package ru.hznik.devicebridge.domain.file

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SafeFilenameResolverTest {

    @Test
    fun stripsPathsDotSegmentsControlsAndBidiCharacters() {
        assertEquals("passwd", SafeFilenameResolver.normalize("../../etc/passwd", "abc123"))
        assertEquals("photo.jpg", SafeFilenameResolver.normalize("C:\\temp\\photo.jpg", "abc123"))
        assertEquals("report.txt", SafeFilenameResolver.normalize("re\u0000port\u202E.txt", "abc123"))
        assertEquals("file-abc123", SafeFilenameResolver.normalize("..", "abc123"))
        assertEquals("file-abc123", SafeFilenameResolver.normalize(".\u0001 ", "abc123"))
    }

    @Test
    fun limitsLengthWhilePreservingASafeExtension() {
        val normalized = SafeFilenameResolver.normalize(
            rawName = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa.document.pdf",
            fallbackId = "abc123",
        )

        assertTrue(normalized.length <= MAX_SAFE_FILENAME_LENGTH)
        assertTrue(normalized.endsWith(".pdf"))
        assertFalse(normalized.contains('/'))
        assertFalse(normalized.contains('\\'))
    }

    @Test
    fun usesVisibleIncrementingNamesWithoutOverwriting() {
        val existing = setOf("photo.jpg", "photo (1).jpg", "PHOTO (2).JPG")

        assertEquals(
            "photo (3).jpg",
            SafeFilenameResolver.resolveCollision("photo.jpg", existing),
        )
        assertEquals(
            "archive (1)",
            SafeFilenameResolver.resolveCollision("archive", setOf("archive")),
        )
        assertEquals(
            "new.txt",
            SafeFilenameResolver.resolveCollision("new.txt", existing),
        )
    }

    @Test
    fun reservedOrEmptyWindowsNamesReceiveNeutralFallback() {
        assertEquals("file-id1.txt", SafeFilenameResolver.normalize("CON.txt", "id1"))
        assertEquals("file-id1", SafeFilenameResolver.normalize("   ... ", "id1"))
        assertEquals("file-id1", SafeFilenameResolver.normalize("", "id1"))
    }
}
