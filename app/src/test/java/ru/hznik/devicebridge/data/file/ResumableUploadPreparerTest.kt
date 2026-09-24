package ru.hznik.devicebridge.data.file

import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.channels.Channels
import java.security.MessageDigest
import kotlin.io.path.createTempDirectory
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.hznik.devicebridge.domain.file.FileTransferDirection
import ru.hznik.devicebridge.domain.file.FileTransferId
import ru.hznik.devicebridge.domain.file.FileTransferMetadata

class ResumableUploadPreparerTest {
    private val content = ByteArray((RESUMABLE_UPLOAD_MIN_BYTES + 1_000).toInt()) { (it % 251).toByte() }
    private val metadata = FileTransferMetadata(
        id = FileTransferId("transfer-1"),
        displayName = "movie.mp4",
        sizeBytes = content.size.toLong(),
        mimeType = "video/mp4",
        sha256 = sha256(content),
        direction = FileTransferDirection.BROWSER_TO_ANDROID,
    )

    @Test
    fun retainedPrefixContinuesWithRebuiltChecksum() = runTest {
        val fixture = Fixture()
        val retained = 3_000_000
        fixture.retain(content.copyOf(retained))

        val prepared = fixture.preparer.prepare(TREE, metadata, resume = true)

        assertEquals(retained.toLong(), prepared.offsetBytes)
        prepared.output.use { it.write(content, retained, content.size - retained) }
        assertEquals(metadata.sha256, prepared.digest.update(content, retained, content.size - retained).let {
            prepared.digest.digest().toHex()
        })
        assertArrayEquals(content, fixture.fileOf(prepared.handle.documentUri).readBytes())
        assertEquals("movie.mp4", prepared.handle.finalDisplayName)
    }

    @Test
    fun theStoredLengthIsTheTruthNotTheRecordedCount() = runTest {
        val fixture = Fixture()
        fixture.retain(content.copyOf(1_234_567), recordedBytes = 9_999_999)

        val prepared = fixture.preparer.prepare(TREE, metadata, resume = true)

        assertEquals(1_234_567L, prepared.offsetBytes)
    }

    @Test
    fun corruptedPrefixStillResumesAndOnlyTheFinalChecksumCatchesIt() = runTest {
        val fixture = Fixture()
        val damaged = content.copyOf(2_000_000).also { it[10] = (it[10] + 1).toByte() }
        fixture.retain(damaged)

        val prepared = fixture.preparer.prepare(TREE, metadata, resume = true)
        prepared.digest.update(content, 2_000_000, content.size - 2_000_000)

        assertTrue(prepared.digest.digest().toHex() != metadata.sha256)
    }

    @Test
    fun providerWithoutSeekRestartsFromZeroAndDropsTheOldPartial() = runTest {
        val fixture = Fixture(seekable = false)
        val old = fixture.retain(content.copyOf(3_000_000))

        val prepared = fixture.preparer.prepare(TREE, metadata, resume = true)

        assertEquals(0L, prepared.offsetBytes)
        assertTrue(old !in fixture.files)
        assertNull(fixture.store.records[old])
    }

    @Test
    fun otherFolderSmallFileOrLegacyUploadStartFromZero() = runTest {
        val fixture = Fixture()
        fixture.retain(content.copyOf(3_000_000))

        assertEquals(0L, fixture.preparer.prepare("content://other", metadata, resume = true).offsetBytes)
        assertEquals(0L, fixture.preparer.prepare(TREE, metadata, resume = false).offsetBytes)
        val small = metadata.copy(sizeBytes = 100)
        val prepared = fixture.preparer.prepare(TREE, small, resume = true)
        assertEquals(0L, prepared.offsetBytes)
        assertNull(prepared.resumeKey)
    }

    @Test
    fun oversizedPrefixIsDiscarded() = runTest {
        val fixture = Fixture()
        val old = fixture.retain(content + ByteArray(10))

        val prepared = fixture.preparer.prepare(TREE, metadata, resume = true)

        assertEquals(0L, prepared.offsetBytes)
        assertTrue(old !in fixture.files)
    }

    private class Fixture(seekable: Boolean = true) {
        val directory: File = createTempDirectory("resume").toFile()
        val files = mutableMapOf<String, File>()
        val store = MapStore()
        private var next = 0
        private val provider = object : PartialDocumentProvider {
            override suspend fun listDisplayNames(treeUri: String): Set<String> = emptySet()
            override suspend fun create(treeUri: String, mimeType: String, displayName: String): String {
                next += 1
                val uri = "content://doc/$next"
                files[uri] = File(directory, "doc-$next").also { it.createNewFile() }
                return uri
            }
            override suspend fun rename(documentUri: String, displayName: String): String = documentUri
            override suspend fun delete(documentUri: String): Boolean = files.remove(documentUri)?.delete() == true
            override suspend fun size(documentUri: String): Long? = files[documentUri]?.length()
        }
        private val io = object : DocumentIo {
            override suspend fun openFresh(documentUri: String): OutputStream =
                FileOutputStream(files.getValue(documentUri))

            override suspend fun openForResume(documentUri: String): ResumableDocument? {
                if (!seekable) return null
                val file = RandomAccessFile(files.getValue(documentUri), "rw")
                return object : ResumableDocument {
                    override fun readPrefix(consumer: (ByteArray, Int) -> Unit): Long {
                        val buffer = ByteArray(64 * 1024)
                        var total = 0L
                        file.seek(0)
                        while (true) {
                            val read = file.read(buffer)
                            if (read < 0) break
                            consumer(buffer, read)
                            total += read
                        }
                        return total
                    }

                    override fun outputAt(offset: Long): OutputStream {
                        file.setLength(offset)
                        file.seek(offset)
                        return Channels.newOutputStream(file.channel)
                    }

                    override fun close() = file.close()
                }
            }
        }
        val preparer = ResumableUploadPreparer(PartialDocumentManager(provider), store, io)

        fun retain(bytes: ByteArray, recordedBytes: Long = bytes.size.toLong()): String {
            next += 1
            val uri = "content://doc/$next"
            files[uri] = File(directory, "doc-$next").also { it.writeBytes(bytes) }
            store.records[uri] = PartialUploadRecord(
                key = PartialUploadKey(SHA, SIZE, "movie.mp4", TREE),
                documentUri = uri,
                mimeType = "video/mp4",
                bytesRetained = recordedBytes,
                updatedAtEpochMillis = 0,
            )
            return uri
        }

        fun fileOf(uri: String): File = files.getValue(uri)

        inner class MapStore : PartialUploadStore {
            val records = mutableMapOf<String, PartialUploadRecord>()
            override val summary: Flow<PartialUploadSummary> = flowOf(PartialUploadSummary(0, 0))
            override suspend fun find(key: PartialUploadKey) =
                records.values.firstOrNull { it.key == key && files.containsKey(it.documentUri) }
            override suspend fun save(record: PartialUploadRecord) { records[record.documentUri] = record }
            override suspend fun forget(documentUri: String) { records.remove(documentUri) }
            override suspend fun discard(documentUri: String) {
                records.remove(documentUri)
                files.remove(documentUri)?.delete()
            }
            override suspend fun cleanup(nowEpochMillis: Long) = 0
            override suspend fun discardAll() = 0
        }
    }

    private companion object {
        const val TREE = "content://tree/download"
        val SIZE = RESUMABLE_UPLOAD_MIN_BYTES + 1_000
        val SHA: String = sha256(ByteArray((RESUMABLE_UPLOAD_MIN_BYTES + 1_000).toInt()) { (it % 251).toByte() })

        fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).toHex()

        fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
    }
}
