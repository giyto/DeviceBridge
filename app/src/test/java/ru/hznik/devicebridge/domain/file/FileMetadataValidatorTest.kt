package ru.hznik.devicebridge.domain.file

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FileMetadataValidatorTest {

    @Test
    fun acceptsZeroFiveHundredMiBAndExactlyOneGiB() {
        listOf(0L, 500L * 1024 * 1024, HARD_MAX_FILE_BYTES).forEach { size ->
            val result = FileMetadataValidator.validate(candidate(sizeBytes = size))

            assertTrue("Expected $size bytes to be valid", result is FileMetadataValidation.Valid)
            assertEquals(size, (result as FileMetadataValidation.Valid).metadata.sizeBytes)
        }
    }

    @Test
    fun rejectsNegativeAndOneByteAboveHardLimit() {
        assertInvalid(-1, FileMetadataError.INVALID_SIZE)
        assertInvalid(HARD_MAX_FILE_BYTES + 1, FileMetadataError.FILE_TOO_LARGE)
    }

    @Test
    fun fallsBackToGenericMimeForMissingOrHostileValues() {
        listOf(null, "", "  ", "text/plain\nmalicious", "x".repeat(128)).forEach { mime ->
            val result = FileMetadataValidator.validate(candidate(mimeType = mime))

            assertEquals(
                DEFAULT_FILE_MIME_TYPE,
                (result as FileMetadataValidation.Valid).metadata.mimeType,
            )
        }
    }

    @Test
    fun batchBoundsAreOneThroughThirtyTwoAndIdsMustBeOpaque() {
        assertEquals(
            FileBatchValidation.Empty,
            FileMetadataValidator.validateBatch(emptyList()),
        )
        assertTrue(
            FileMetadataValidator.validateBatch(List(MAX_FILE_BATCH_SIZE) { candidate(id = "file-$it") })
                is FileBatchValidation.Valid,
        )
        assertEquals(
            FileBatchValidation.TooMany,
            FileMetadataValidator.validateBatch(
                List(MAX_FILE_BATCH_SIZE + 1) { candidate(id = "file-$it") },
            ),
        )
        listOf("", "bad id", "../file", "x".repeat(65)).forEach { id ->
            val result = FileMetadataValidator.validate(candidate(id = id))
            assertEquals(
                FileMetadataError.INVALID_TRANSFER_ID,
                (result as FileMetadataValidation.Invalid).error,
            )
        }
        assertEquals(
            FileBatchValidation.DuplicateTransferId,
            FileMetadataValidator.validateBatch(
                listOf(candidate(id = "same"), candidate(id = "same")),
            ),
        )
    }

    @Test
    fun actualSizeMustMatchValidatedMetadata() {
        val metadata = (
            FileMetadataValidator.validate(candidate(sizeBytes = 512))
                as FileMetadataValidation.Valid
            ).metadata

        assertEquals(null, FileMetadataValidator.validateActualSize(metadata, 512))
        assertEquals(
            FileMetadataError.SIZE_MISMATCH,
            FileMetadataValidator.validateActualSize(metadata, 511),
        )
        assertEquals(
            FileMetadataError.FILE_TOO_LARGE,
            FileMetadataValidator.validateActualSize(metadata, HARD_MAX_FILE_BYTES + 1),
        )
    }

    private fun assertInvalid(size: Long, expected: FileMetadataError) {
        val result = FileMetadataValidator.validate(candidate(sizeBytes = size))
        assertEquals(expected, (result as FileMetadataValidation.Invalid).error)
    }

    private fun candidate(
        id: String = "transfer-1",
        sizeBytes: Long = 512,
        mimeType: String? = "image/jpeg",
    ) = FileMetadataCandidate(
        transferId = id,
        displayName = "photo.jpg",
        sizeBytes = sizeBytes,
        mimeType = mimeType,
        sha256 = "a".repeat(64),
        direction = FileTransferDirection.BROWSER_TO_ANDROID,
    )
}
