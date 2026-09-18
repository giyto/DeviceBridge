package ru.hznik.devicebridge.data.persistence

import android.content.Context
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import ru.hznik.devicebridge.data.persistence.room.DeviceBridgeDatabase
import ru.hznik.devicebridge.data.persistence.room.HistoryRecordEntity
import ru.hznik.devicebridge.data.persistence.room.TrustedBrowserEntity

@RunWith(AndroidJUnit4::class)
class DeviceBridgeDatabaseTest {
    private lateinit var database: DeviceBridgeDatabase

    @Before
    fun createDatabase() {
        val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
        database = Room.inMemoryDatabaseBuilder(
            context,
            DeviceBridgeDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @After
    fun closeDatabase() {
        database.close()
    }

    @Test
    fun historyInsertIsOrderedAndOperationKindConflictIsIgnored() = runBlocking {
        val older = historyEntity(
            recordId = "record-old",
            operationId = "operation-shared",
            timestampEpochMillis = 100,
        )
        val newerConflict = historyEntity(
            recordId = "record-new",
            operationId = "operation-shared",
            timestampEpochMillis = 200,
        )
        val independent = historyEntity(
            recordId = "record-independent",
            operationId = "operation-independent",
            timestampEpochMillis = 300,
        )

        assertNotEquals(-1L, database.historyDao().insert(older))
        assertEquals(-1L, database.historyDao().insert(newerConflict))
        assertNotEquals(-1L, database.historyDao().insert(independent))

        assertEquals(
            listOf("record-independent", "record-old"),
            database.historyDao().observeAll().first().map(HistoryRecordEntity::recordId),
        )
    }

    @Test
    fun trustedBrowserCanBeInsertedQueriedAndDeleted() = runBlocking {
        val entity = TrustedBrowserEntity(
            trustedBrowserId = "trusted-1",
            browserLabel = "Yandex Browser",
            createdAtEpochMillis = 100,
            lastUsedAtEpochMillis = null,
            expiresAtEpochMillis = 1_000,
            credentialVerifier = byteArrayOf(1, 2, 3),
        )

        database.trustedBrowserDao().insert(entity)
        val stored = requireNotNull(database.trustedBrowserDao().findById("trusted-1"))
        assertEquals(entity.trustedBrowserId, stored.trustedBrowserId)
        assertEquals(entity.browserLabel, stored.browserLabel)
        assertEquals(entity.createdAtEpochMillis, stored.createdAtEpochMillis)
        assertEquals(entity.lastUsedAtEpochMillis, stored.lastUsedAtEpochMillis)
        assertEquals(entity.expiresAtEpochMillis, stored.expiresAtEpochMillis)
        assertEquals(true, entity.credentialVerifier.contentEquals(stored.credentialVerifier))

        assertEquals(1, database.trustedBrowserDao().deleteById("trusted-1"))
        assertEquals(emptyList<TrustedBrowserEntity>(), database.trustedBrowserDao().observeAll().first())
    }

    private fun historyEntity(
        recordId: String,
        operationId: String,
        timestampEpochMillis: Long,
    ) = HistoryRecordEntity(
        recordId = recordId,
        operationId = operationId,
        kind = "text",
        direction = "android_to_browser",
        browserLabel = "Chrome",
        timestampEpochMillis = timestampEpochMillis,
        terminalStatus = "delivered",
        textPreview = "hello",
        fileDisplayName = null,
        fileSizeBytes = null,
        fileMimeType = null,
        fileSha256 = null,
        failureReason = null,
    )
}
