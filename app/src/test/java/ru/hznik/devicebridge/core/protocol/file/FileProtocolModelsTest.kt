package ru.hznik.devicebridge.core.protocol.file

import kotlinx.serialization.SerializationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class FileProtocolModelsTest {

    @Test
    fun allFileDtosRoundTripThroughStrictJson() {
        val metadata = validMetadata()
        val offer = FileOfferMessage(
            protocolVersion = FILE_PROTOCOL_VERSION,
            messageId = "offer-1",
            type = FILE_OFFER_TYPE,
            timestamp = 1_000,
            batchId = "batch-1",
            items = listOf(metadata),
        )
        val ready = FileUploadReadyEvent(
            protocolVersion = FILE_PROTOCOL_VERSION,
            messageId = "ready-1",
            type = FILE_UPLOAD_READY_TYPE,
            timestamp = 1_100,
            transferId = metadata.transferId,
        )
        val progress = FileProgressEvent(
            protocolVersion = FILE_PROTOCOL_VERSION,
            messageId = "progress-1",
            type = FILE_PROGRESS_TYPE,
            timestamp = 1_200,
            transferId = metadata.transferId,
            status = FileTransferStatusDto.TRANSFERRING,
            bytesTransferred = 128,
            totalBytes = metadata.sizeBytes,
            speedBytesPerSecond = 256,
        )
        val grant = FileDownloadGrantResponse(
            protocolVersion = FILE_PROTOCOL_VERSION,
            messageId = "grant-1",
            type = FILE_DOWNLOAD_GRANT_TYPE,
            timestamp = 1_300,
            transferId = metadata.transferId,
            downloadPath = "/api/v1/files/transfer-1?grant=opaque",
            expiresAt = 31_300,
        )
        val verification = FileVerificationMessage(
            protocolVersion = FILE_PROTOCOL_VERSION,
            messageId = "verify-1",
            type = FILE_VERIFY_TYPE,
            timestamp = 1_400,
            transferId = metadata.transferId,
            sizeBytes = metadata.sizeBytes,
            sha256 = metadata.sha256,
        )
        val cancellation = FileCancellationMessage(
            protocolVersion = FILE_PROTOCOL_VERSION,
            messageId = "cancel-1",
            type = FILE_CANCEL_TYPE,
            timestamp = 1_500,
            transferId = metadata.transferId,
        )
        val snapshot = FileSnapshotEvent(
            protocolVersion = FILE_PROTOCOL_VERSION,
            messageId = "snapshot-1",
            type = FILE_SNAPSHOT_TYPE,
            timestamp = 1_600,
            items = listOf(
                FileSnapshotItem(
                    metadata = metadata,
                    status = FileTransferStatusDto.QUEUED,
                    bytesTransferred = 0,
                    speedBytesPerSecond = 0,
                ),
            ),
        )
        val error = FileErrorEvent(
            protocolVersion = FILE_PROTOCOL_VERSION,
            messageId = "error-1",
            type = FILE_ERROR_TYPE,
            timestamp = 1_700,
            relatedMessageId = offer.messageId,
            transferId = metadata.transferId,
            code = FileProtocolErrorCode.CHECKSUM_MISMATCH,
        )

        assertRoundTrip(offer)
        assertRoundTrip(ready)
        assertRoundTrip(progress)
        assertRoundTrip(grant)
        assertRoundTrip(verification)
        assertRoundTrip(cancellation)
        assertRoundTrip(snapshot)
        assertRoundTrip(error)
    }

    @Test
    fun strictJsonRejectsUnknownFields() {
        val payload = """{
            "protocolVersion":1,
            "messageId":"cancel-1",
            "type":"file.cancel",
            "timestamp":1000,
            "transferId":"transfer-1",
            "unexpected":true
        }""".trimIndent()

        assertThrows(SerializationException::class.java) {
            FileProtocolJson.decode<FileCancellationMessage>(payload)
        }
    }

    @Test
    fun validatorBoundsEnvelopeBatchesAndSnapshot() {
        val offer = validOffer()
        val snapshot = FileSnapshotEvent(
            protocolVersion = FILE_PROTOCOL_VERSION,
            messageId = "snapshot-1",
            type = FILE_SNAPSHOT_TYPE,
            timestamp = 1_000,
            items = listOf(
                FileSnapshotItem(
                    metadata = validMetadata(),
                    status = FileTransferStatusDto.QUEUED,
                    bytesTransferred = 0,
                    speedBytesPerSecond = 0,
                ),
            ),
        )

        assertEquals(FileProtocolValidationError.NONE, FileProtocolValidator.validate(offer))
        assertEquals(
            FileProtocolValidationError.UNSUPPORTED_VERSION,
            FileProtocolValidator.validate(offer.copy(protocolVersion = 2)),
        )
        assertEquals(
            FileProtocolValidationError.INVALID_TYPE,
            FileProtocolValidator.validate(offer.copy(type = "wrong")),
        )
        assertEquals(
            FileProtocolValidationError.INVALID_MESSAGE_ID,
            FileProtocolValidator.validate(offer.copy(messageId = "bad id")),
        )
        assertEquals(
            FileProtocolValidationError.INVALID_BATCH,
            FileProtocolValidator.validate(offer.copy(items = emptyList())),
        )
        assertEquals(
            FileProtocolValidationError.INVALID_BATCH,
            FileProtocolValidator.validate(
                offer.copy(items = List(MAX_FILE_BATCH_ITEMS + 1) { validMetadata("transfer-$it") }),
            ),
        )
        assertEquals(
            FileProtocolValidationError.INVALID_SNAPSHOT,
            FileProtocolValidator.validate(
                snapshot.copy(items = List(MAX_FILE_SNAPSHOT_ITEMS + 1) { snapshot.items.single() }),
            ),
        )
    }

    @Test
    fun validatorChecksTransferProgressGrantVerificationAndErrorReferences() {
        val metadata = validMetadata()
        val progress = FileProgressEvent(
            protocolVersion = FILE_PROTOCOL_VERSION,
            messageId = "progress-1",
            type = FILE_PROGRESS_TYPE,
            timestamp = 1_000,
            transferId = metadata.transferId,
            status = FileTransferStatusDto.TRANSFERRING,
            bytesTransferred = metadata.sizeBytes,
            totalBytes = metadata.sizeBytes,
            speedBytesPerSecond = 1,
        )
        val grant = FileDownloadGrantResponse(
            protocolVersion = FILE_PROTOCOL_VERSION,
            messageId = "grant-1",
            type = FILE_DOWNLOAD_GRANT_TYPE,
            timestamp = 1_000,
            transferId = metadata.transferId,
            downloadPath = "/api/v1/files/transfer-1?grant=opaque",
            expiresAt = 2_000,
        )
        val verification = FileVerificationMessage(
            protocolVersion = FILE_PROTOCOL_VERSION,
            messageId = "verify-1",
            type = FILE_VERIFY_TYPE,
            timestamp = 1_000,
            transferId = metadata.transferId,
            sizeBytes = metadata.sizeBytes,
            sha256 = metadata.sha256,
        )
        val error = FileErrorEvent(
            protocolVersion = FILE_PROTOCOL_VERSION,
            messageId = "error-1",
            type = FILE_ERROR_TYPE,
            timestamp = 1_000,
            relatedMessageId = "offer-1",
            transferId = metadata.transferId,
            code = FileProtocolErrorCode.INVALID_PAYLOAD,
        )

        assertEquals(FileProtocolValidationError.NONE, FileProtocolValidator.validate(progress))
        assertEquals(FileProtocolValidationError.NONE, FileProtocolValidator.validate(grant))
        assertEquals(FileProtocolValidationError.NONE, FileProtocolValidator.validate(verification))
        assertEquals(FileProtocolValidationError.NONE, FileProtocolValidator.validate(error))
        assertEquals(
            FileProtocolValidationError.INVALID_PROGRESS,
            FileProtocolValidator.validate(progress.copy(bytesTransferred = metadata.sizeBytes + 1)),
        )
        assertEquals(
            FileProtocolValidationError.INVALID_DOWNLOAD_PATH,
            FileProtocolValidator.validate(grant.copy(downloadPath = "https://example.com/file")),
        )
        assertEquals(
            FileProtocolValidationError.INVALID_CHECKSUM,
            FileProtocolValidator.validate(verification.copy(sha256 = "not-a-sha")),
        )
        assertEquals(
            FileProtocolValidationError.INVALID_RELATED_MESSAGE_ID,
            FileProtocolValidator.validate(error.copy(relatedMessageId = "bad id")),
        )
    }

    private inline fun <reified T> assertRoundTrip(value: T) {
        assertEquals(value, FileProtocolJson.decode<T>(FileProtocolJson.encode(value)))
    }

    private fun validOffer() = FileOfferMessage(
        protocolVersion = FILE_PROTOCOL_VERSION,
        messageId = "offer-1",
        type = FILE_OFFER_TYPE,
        timestamp = 1_000,
        batchId = "batch-1",
        items = listOf(validMetadata()),
    )

    private fun validMetadata(transferId: String = "transfer-1") = FileMetadataDto(
        transferId = transferId,
        displayName = "photo.jpg",
        sizeBytes = 512,
        mimeType = "image/jpeg",
        sha256 = "a".repeat(64),
        direction = FileDirectionDto.BROWSER_TO_ANDROID,
    )
}
