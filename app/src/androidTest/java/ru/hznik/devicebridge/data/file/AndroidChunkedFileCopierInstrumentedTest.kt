package ru.hznik.devicebridge.data.file

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidChunkedFileCopierInstrumentedTest {

    @Test
    fun copiesAndHashesRealAndroidFileStreams() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val source = context.noBackupFilesDir.resolve("chunked-source.bin")
        val destination = context.noBackupFilesDir.resolve("chunked-destination.bin")
        val bytes = ByteArray(1024 * 1024) { index -> (index and 0xFF).toByte() }
        source.writeBytes(bytes)
        try {
            val result = source.inputStream().use { input ->
                destination.outputStream().use { output ->
                    AndroidChunkedFileCopier(bufferSize = 16 * 1024).copy(input, output)
                }
            }

            assertEquals(bytes.size.toLong(), result.sizeBytes)
            assertEquals(sha256(bytes), result.sha256)
            assertEquals(bytes.size.toLong(), destination.length())
        } finally {
            source.delete()
            destination.delete()
        }
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { byte -> "%02x".format(byte) }
}
