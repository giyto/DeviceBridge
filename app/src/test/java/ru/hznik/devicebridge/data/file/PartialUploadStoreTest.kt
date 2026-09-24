package ru.hznik.devicebridge.data.file

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import ru.hznik.devicebridge.data.persistence.room.PartialUploadDao
import ru.hznik.devicebridge.data.persistence.room.PartialUploadEntity

class PartialUploadStoreTest {
    private val hour = 60L * 60 * 1000

    @Test
    fun findMatchesTheWholeKeyAndIgnoresShaCase() = runTest {
        val fixture = Fixture()
        fixture.store.save(record("doc-1", bytes = 10, updatedAt = 0))

        assertEquals(10L, fixture.store.find(KEY.copy(sha256 = KEY.sha256.uppercase()))?.bytesRetained)
        assertNull(fixture.store.find(KEY.copy(treeUri = "content://other")))
        assertNull(fixture.store.find(KEY.copy(displayName = "other.mp4")))
        assertNull(fixture.store.find(KEY.copy(sizeBytes = KEY.sizeBytes + 1)))
    }

    @Test
    fun findForgetsARecordWhosePartialWasDeletedOutsideTheApp() = runTest {
        val fixture = Fixture()
        fixture.store.save(record("doc-1", bytes = 10, updatedAt = 0))
        fixture.documents.sizes.remove("doc-1")

        assertNull(fixture.store.find(KEY))
        assertEquals(0, fixture.dao.rows.value.size)
    }

    @Test
    fun cleanupDeletesExpiredPartialsAndMissingRecordsOnly() = runTest {
        val fixture = Fixture()
        fixture.store.save(record("expired", bytes = 10, updatedAt = 0, name = "a.bin"))
        fixture.store.save(record("fresh", bytes = 20, updatedAt = 23 * hour, name = "b.bin"))
        fixture.store.save(record("gone", bytes = 30, updatedAt = 23 * hour, name = "c.bin"))
        fixture.documents.sizes.remove("gone")

        assertEquals(2, fixture.store.cleanup(nowEpochMillis = 24 * hour))

        assertEquals(listOf("fresh"), fixture.dao.rows.value.map { it.partialDocumentUri })
        assertEquals(listOf("expired"), fixture.documents.deleted)
    }

    @Test
    fun summaryAndDiscardAllCoverEveryRetainedPartial() = runTest {
        val fixture = Fixture()
        fixture.store.save(record("doc-1", bytes = 10, updatedAt = 0, name = "a.bin"))
        fixture.store.save(record("doc-2", bytes = 32, updatedAt = 0, name = "b.bin"))
        assertEquals(PartialUploadSummary(count = 2, totalBytes = 42), fixture.store.summary.first())

        assertEquals(2, fixture.store.discardAll())

        assertEquals(PartialUploadSummary(count = 0, totalBytes = 0), fixture.store.summary.first())
        assertEquals(listOf("doc-1", "doc-2"), fixture.documents.deleted)
    }

    @Test
    fun aNewerRetainOfTheSameFileReplacesTheOlderRecord() = runTest {
        val fixture = Fixture()
        fixture.store.save(record("doc-1", bytes = 10, updatedAt = 0))
        fixture.store.save(record("doc-1", bytes = 50, updatedAt = 1))

        assertEquals(1, fixture.dao.rows.value.size)
        assertEquals(50L, fixture.store.find(KEY)?.bytesRetained)
    }

    private class Fixture {
        val dao = FakeDao()
        val documents = FakeDocuments()
        val store = RoomPartialUploadStore(dao, documents)
    }

    private fun record(
        documentUri: String,
        bytes: Long,
        updatedAt: Long,
        name: String = KEY.displayName,
    ) = PartialUploadRecord(
        key = KEY.copy(displayName = name),
        documentUri = documentUri,
        mimeType = "video/mp4",
        bytesRetained = bytes,
        updatedAtEpochMillis = updatedAt,
    )

    private class FakeDocuments : PartialDocumentProvider {
        val sizes = mutableMapOf(
            "doc-1" to 10L, "doc-2" to 32L, "expired" to 10L, "fresh" to 20L, "gone" to 30L,
        )
        val deleted = mutableListOf<String>()
        override suspend fun listDisplayNames(treeUri: String): Set<String> = emptySet()
        override suspend fun create(treeUri: String, mimeType: String, displayName: String): String? = null
        override suspend fun rename(documentUri: String, displayName: String): String? = null
        override suspend fun delete(documentUri: String): Boolean {
            deleted += documentUri
            return sizes.remove(documentUri) != null
        }
        override suspend fun size(documentUri: String): Long? = sizes[documentUri]
    }

    private class FakeDao : PartialUploadDao {
        val rows = MutableStateFlow<List<PartialUploadEntity>>(emptyList())

        override suspend fun upsert(partial: PartialUploadEntity) {
            rows.value = rows.value.filterNot {
                it.partialDocumentUri == partial.partialDocumentUri ||
                    (it.sha256 == partial.sha256 && it.sizeBytes == partial.sizeBytes &&
                        it.displayName == partial.displayName && it.treeUri == partial.treeUri)
            } + partial
        }

        override suspend fun find(sha256: String, sizeBytes: Long, displayName: String, treeUri: String) =
            rows.value.firstOrNull {
                it.sha256 == sha256 && it.sizeBytes == sizeBytes &&
                    it.displayName == displayName && it.treeUri == treeUri
            }

        override suspend fun getAll(): List<PartialUploadEntity> = rows.value

        override fun observeAll(): Flow<List<PartialUploadEntity>> = rows.map { it }

        override suspend fun delete(partialDocumentUri: String): Int {
            val before = rows.value.size
            rows.value = rows.value.filterNot { it.partialDocumentUri == partialDocumentUri }
            return before - rows.value.size
        }
    }

    private companion object {
        val KEY = PartialUploadKey(
            sha256 = "a".repeat(64),
            sizeBytes = 100,
            displayName = "movie.mp4",
            treeUri = "content://tree/download",
        )
    }
}
