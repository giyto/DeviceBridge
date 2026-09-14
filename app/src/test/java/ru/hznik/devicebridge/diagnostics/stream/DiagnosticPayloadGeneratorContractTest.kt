package ru.hznik.devicebridge.diagnostics.stream

import java.security.MessageDigest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticPayloadGeneratorContractTest {

    @Test
    fun payloadIsStableAcrossChunkSizesAndShaIsIncremental() {
        val result = runCatching {
            val generatorClass = Class.forName(
                "ru.hznik.devicebridge.diagnostics.stream.DiagnosticPayloadGenerator",
                false,
                javaClass.classLoader,
            )
            val constructor = generatorClass.getConstructor(
                Long::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
            )
            val first = constructor.newInstance(PAYLOAD_SIZE, 4_096)
            val second = constructor.newInstance(PAYLOAD_SIZE, 8_191)

            val firstBytes = readAll(first)
            val secondBytes = readAll(second)

            assertArrayEquals(firstBytes, secondBytes)
            assertEquals(PAYLOAD_SIZE.toInt(), firstBytes.size)

            val expectedSha = MessageDigest.getInstance("SHA-256")
                .digest(firstBytes)
                .toHex()
            val accumulatorClass = Class.forName(
                "ru.hznik.devicebridge.diagnostics.stream.StreamingSha256",
                false,
                javaClass.classLoader,
            )
            val accumulator = accumulatorClass.getDeclaredConstructor().newInstance()
            val update = accumulatorClass.getMethod(
                "update",
                ByteArray::class.java,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
            )
            var digestOffset = 0
            while (digestOffset < firstBytes.size) {
                val length = minOf(997, firstBytes.size - digestOffset)
                update.invoke(accumulator, firstBytes, digestOffset, length)
                digestOffset += length
            }
            assertEquals(
                PAYLOAD_SIZE,
                accumulatorClass.getMethod("getBytesProcessed").invoke(accumulator),
            )
            assertEquals(
                expectedSha,
                accumulatorClass.getMethod("digestHex").invoke(accumulator),
            )
            assertEquals(expectedSha, first.readSha())
            assertEquals(expectedSha, second.readSha())

            val empty = constructor.newInstance(0L, 1_024)
            assertEquals(0, readAll(empty).size)
            assertEquals(
                MessageDigest.getInstance("SHA-256").digest().toHex(),
                empty.readSha(),
            )
        }

        assertTrue(
            result.exceptionOrNull()?.stackTraceToString() ?: "Payload contract failed",
            result.isSuccess,
        )
    }

    private fun readAll(generator: Any): ByteArray {
        val totalBytes = generator.javaClass.getMethod("getTotalBytes")
            .invoke(generator) as Long
        val chunkSize = generator.javaClass.getMethod("getChunkSize")
            .invoke(generator) as Int
        val read = generator.javaClass.getMethod(
            "read",
            Long::class.javaPrimitiveType,
            ByteArray::class.java,
        )
        val output = ByteArray(totalBytes.toInt())
        val buffer = ByteArray(chunkSize)
        var offset = 0L
        while (offset < totalBytes) {
            val count = read.invoke(generator, offset, buffer) as Int
            assertTrue(count in 1..buffer.size)
            buffer.copyInto(output, destinationOffset = offset.toInt(), endIndex = count)
            offset += count
        }
        return output
    }

    private fun Any.readSha(): String {
        return javaClass.getMethod("sha256").invoke(this) as String
    }

    private fun ByteArray.toHex(): String = joinToString(separator = "") { byte ->
        "%02x".format(byte)
    }

    private companion object {
        const val PAYLOAD_SIZE = 100_003L
    }
}
