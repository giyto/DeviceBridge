package ru.hznik.devicebridge.diagnostics.stream

import java.security.MessageDigest

class DiagnosticPayloadGenerator(
    val totalBytes: Long,
    val chunkSize: Int,
) {
    init {
        require(totalBytes >= 0) { "totalBytes must be non-negative" }
        require(chunkSize > 0) { "chunkSize must be positive" }
    }

    fun read(
        offset: Long,
        target: ByteArray,
    ): Int {
        require(offset >= 0) { "offset must be non-negative" }
        if (offset >= totalBytes || target.isEmpty()) {
            return 0
        }

        val count = minOf(
            target.size.toLong(),
            totalBytes - offset,
        ).toInt()
        for (index in 0 until count) {
            target[index] = deterministicByte(offset + index)
        }
        return count
    }

    fun sha256(): String {
        val digest = StreamingSha256()
        val buffer = ByteArray(chunkSize)
        var offset = 0L
        while (offset < totalBytes) {
            val count = read(offset, buffer)
            digest.update(buffer, offset = 0, length = count)
            offset += count
        }
        return digest.digestHex()
    }

    private fun deterministicByte(absoluteOffset: Long): Byte {
        val mixed = absoluteOffset xor
            (absoluteOffset ushr 7) xor
            (absoluteOffset ushr 17) xor
            PATTERN_SEED
        return (mixed and 0xFF).toByte()
    }

    private companion object {
        const val PATTERN_SEED = 0xA5L
    }
}

class StreamingSha256 {
    private val digest = MessageDigest.getInstance("SHA-256")
    private var finalHex: String? = null

    var bytesProcessed: Long = 0
        private set

    fun update(
        bytes: ByteArray,
        offset: Int,
        length: Int,
    ) {
        check(finalHex == null) { "Digest has already been finalized" }
        require(offset >= 0 && length >= 0 && offset + length <= bytes.size) {
            "Invalid byte range"
        }
        digest.update(bytes, offset, length)
        bytesProcessed += length
    }

    fun digestHex(): String {
        finalHex?.let { return it }
        return digest.digest().toHex().also { finalHex = it }
    }

    private fun ByteArray.toHex(): String {
        val output = CharArray(size * 2)
        forEachIndexed { index, byte ->
            val value = byte.toInt() and 0xFF
            output[index * 2] = HEX[value ushr 4]
            output[index * 2 + 1] = HEX[value and 0x0F]
        }
        return output.concatToString()
    }

    private companion object {
        const val HEX = "0123456789abcdef"
    }
}

const val DEFAULT_DIAGNOSTIC_PAYLOAD_BYTES: Long = 500L * 1024L * 1024L
const val DEFAULT_DIAGNOSTIC_CHUNK_BYTES: Int = 64 * 1024
