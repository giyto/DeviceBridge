package ru.hznik.devicebridge.data.persistence

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.hznik.devicebridge.data.persistence.room.HistoryRecordEntity
import ru.hznik.devicebridge.data.persistence.room.TrustedBrowserEntity
import ru.hznik.devicebridge.data.persistence.room.toDomainOrNull
import ru.hznik.devicebridge.data.persistence.room.toEntity
import ru.hznik.devicebridge.domain.history.HistoryDirection
import ru.hznik.devicebridge.domain.history.HistoryFileMetadata
import ru.hznik.devicebridge.domain.history.HistoryKind
import ru.hznik.devicebridge.domain.history.HistoryOperationId
import ru.hznik.devicebridge.domain.history.HistoryRecord
import ru.hznik.devicebridge.domain.history.HistoryRecordId
import ru.hznik.devicebridge.domain.history.HistoryStatus
import ru.hznik.devicebridge.domain.trust.TrustedBrowser
import ru.hznik.devicebridge.domain.trust.TrustedBrowserId

class PersistenceMappersTest {
    @Test
    fun everyHistoryTypeDirectionAndTerminalStatusRoundTrips() {
        val cases = buildList {
            HistoryDirection.entries.forEach { direction ->
                listOf(HistoryKind.TEXT, HistoryKind.LINK).forEach { kind ->
                    listOf(HistoryStatus.DELIVERED, HistoryStatus.FAILED).forEach { status ->
                        add(historyRecord(kind, direction, status))
                    }
                }
                listOf(
                    HistoryStatus.COMPLETED,
                    HistoryStatus.CANCELLED,
                    HistoryStatus.FAILED,
                ).forEach { status ->
                    add(historyRecord(HistoryKind.FILE, direction, status))
                }
            }
        }

        cases.forEach { record ->
            assertEquals(record, record.toEntity().toDomainOrNull())
        }
    }

    @Test
    fun malformedNullableFileColumnsAreRejectedAsOneUnit() {
        val entity = historyRecord(
            HistoryKind.FILE,
            HistoryDirection.BROWSER_TO_ANDROID,
            HistoryStatus.COMPLETED,
        ).toEntity().copy(fileMimeType = null)

        assertNull(entity.toDomainOrNull())
    }

    @Test
    fun trustedBrowserMapperNeverAddsRawCredential() {
        val entity = TrustedBrowserEntity(
            trustedBrowserId = "trusted-1",
            browserLabel = "Edge",
            createdAtEpochMillis = 100,
            lastUsedAtEpochMillis = 200,
            expiresAtEpochMillis = 300,
            credentialVerifier = byteArrayOf(1, 2, 3),
        )

        val domain = entity.toDomainOrNull()

        assertEquals(
            TrustedBrowser(
                id = TrustedBrowserId("trusted-1"),
                browserLabel = "Edge",
                createdAtEpochMillis = 100,
                lastUsedAtEpochMillis = 200,
                expiresAtEpochMillis = 300,
            ),
            domain,
        )
        assertTrue(entity.credentialVerifier.contentEquals(byteArrayOf(1, 2, 3)))
    }

    private fun historyRecord(
        kind: HistoryKind,
        direction: HistoryDirection,
        status: HistoryStatus,
    ): HistoryRecord {
        val suffix = kind.name + "-" + direction.name + "-" + status.name
        return HistoryRecord(
            id = HistoryRecordId("record-" + suffix),
            operationId = HistoryOperationId("operation-" + suffix),
            kind = kind,
            direction = direction,
            browserLabel = "Chrome",
            timestampEpochMillis = 1_700_000_000_000,
            status = status,
            textPreview = if (kind == HistoryKind.FILE) null else "preview",
            file = if (kind == HistoryKind.FILE) {
                HistoryFileMetadata(
                    displayName = "file.bin",
                    sizeBytes = 42,
                    mimeType = "application/octet-stream",
                    sha256 = "a".repeat(64),
                )
            } else {
                null
            },
            failureReason = if (status == HistoryStatus.FAILED) "failed" else null,
        )
    }
}
