package ru.hznik.devicebridge.data.file

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PartialUploadCleanupTest {

    @Test
    fun startupCleanupDropsExpiredPartsSoTheNextOfferStartsFromZero() = runTest {
        val store = ExpiringStore()
        store.saved = record(updatedAt = 0)

        PartialUploadCleanup(store, backgroundScope) { PARTIAL_UPLOAD_TTL_MILLIS }.run().join()

        assertEquals(listOf(PARTIAL_UPLOAD_TTL_MILLIS), store.cleanups)
        assertNull(store.find(KEY))
    }

    @Test
    fun aFailingStoreDoesNotBreakStartup() = runTest {
        val store = ExpiringStore(failing = true)

        PartialUploadCleanup(store, backgroundScope) { 1 }.run().join()

        assertEquals(listOf(1L), store.cleanups)
    }

    private class ExpiringStore(private val failing: Boolean = false) : PartialUploadStore {
        var saved: PartialUploadRecord? = null
        val cleanups = mutableListOf<Long>()
        override val summary: Flow<PartialUploadSummary> = flowOf(PartialUploadSummary(0, 0))
        override suspend fun find(key: PartialUploadKey) = saved?.takeIf { it.key == key }
        override suspend fun save(record: PartialUploadRecord) { saved = record }
        override suspend fun forget(documentUri: String) { saved = null }
        override suspend fun discard(documentUri: String) { saved = null }
        override suspend fun cleanup(nowEpochMillis: Long): Int {
            cleanups += nowEpochMillis
            if (failing) error("provider unavailable")
            val expired = saved?.let { nowEpochMillis - it.updatedAtEpochMillis >= PARTIAL_UPLOAD_TTL_MILLIS }
            if (expired == true) saved = null
            return if (expired == true) 1 else 0
        }
        override suspend fun discardAll() = 0
    }

    private companion object {
        val KEY = PartialUploadKey("a".repeat(64), RESUMABLE_UPLOAD_MIN_BYTES, "movie.mp4", "content://tree")

        fun record(updatedAt: Long) = PartialUploadRecord(
            key = KEY,
            documentUri = "content://doc/1",
            mimeType = "video/mp4",
            bytesRetained = 10,
            updatedAtEpochMillis = updatedAt,
        )
    }
}
