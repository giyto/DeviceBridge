package ru.hznik.devicebridge.data.tls

import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLEngineResult
import javax.net.ssl.SSLException

/**
 * Server-side TLS over plain blocking streams, driven by an [SSLEngine]. Unlike an SSLSocket
 * layered over a socket, it can start from bytes already read off the network, which is how
 * the front door tells TLS from plain HTTP on one port.
 *
 * One thread may read while another writes; the engine allows wrap and unwrap to run
 * concurrently.
 */
internal class TlsEngineStreams(
    private val engine: SSLEngine,
    private val networkIn: InputStream,
    private val networkOut: OutputStream,
) {
    private val readLock = Any()
    private val writeLock = Any()
    private var packetsIn: ByteBuffer = ByteBuffer.allocate(engine.session.packetBufferSize).apply { flip() }
    private var plainIn: ByteBuffer = ByteBuffer.allocate(engine.session.applicationBufferSize).apply { flip() }
    private var packetsOut: ByteBuffer = ByteBuffer.allocate(engine.session.packetBufferSize)
    @Volatile private var inboundDone = false

    val input: InputStream = object : InputStream() {
        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xFF
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
            readPlain(buffer, offset, length)
    }

    val output: OutputStream = object : OutputStream() {
        override fun write(value: Int) = write(byteArrayOf(value.toByte()), 0, 1)

        override fun write(buffer: ByteArray, offset: Int, length: Int) =
            writePlain(buffer, offset, length)

        override fun flush() = networkOut.flush()

        override fun close() = closeOutbound()
    }

    fun handshake() {
        engine.useClientMode = false
        engine.beginHandshake()
        while (true) {
            when (engine.handshakeStatus) {
                SSLEngineResult.HandshakeStatus.NEED_WRAP -> synchronized(writeLock) { wrap(EMPTY) }
                SSLEngineResult.HandshakeStatus.NEED_UNWRAP -> synchronized(readLock) {
                    if (!unwrapOnce()) throw EOFException("Connection closed during the TLS handshake")
                }
                SSLEngineResult.HandshakeStatus.NEED_TASK -> runTasks()
                SSLEngineResult.HandshakeStatus.FINISHED,
                SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING,
                null,
                -> return
            }
        }
    }

    private fun readPlain(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        synchronized(readLock) {
            while (!plainIn.hasRemaining()) {
                if (inboundDone || !unwrapOnce()) return -1
                afterUnwrap()
            }
            val count = minOf(length, plainIn.remaining())
            plainIn.get(buffer, offset, count)
            return count
        }
    }

    private fun writePlain(buffer: ByteArray, offset: Int, length: Int) {
        synchronized(writeLock) {
            val source = ByteBuffer.wrap(buffer, offset, length)
            while (source.hasRemaining()) {
                if (engine.isOutboundDone) throw IOException("TLS connection is closed")
                wrap(source)
            }
        }
    }

    /** Answers whatever the engine needs after reading, such as a key update. */
    private fun afterUnwrap() {
        while (true) {
            when (engine.handshakeStatus) {
                SSLEngineResult.HandshakeStatus.NEED_TASK -> runTasks()
                SSLEngineResult.HandshakeStatus.NEED_WRAP -> synchronized(writeLock) { wrap(EMPTY) }
                else -> return
            }
        }
    }

    /**
     * Decrypts one step into [plainIn], reading more from the network when a record is
     * incomplete. Returns false once the peer has closed.
     */
    private fun unwrapOnce(): Boolean {
        plainIn.compact()
        try {
            while (true) {
                val result = engine.unwrap(packetsIn, plainIn)
                when (result.status) {
                    SSLEngineResult.Status.OK -> return true
                    SSLEngineResult.Status.CLOSED -> {
                        inboundDone = true
                        return false
                    }
                    SSLEngineResult.Status.BUFFER_OVERFLOW ->
                        plainIn = grow(plainIn, engine.session.applicationBufferSize)
                    SSLEngineResult.Status.BUFFER_UNDERFLOW -> if (!readPackets()) {
                        inboundDone = true
                        return false
                    }
                    null -> throw SSLException("Unknown TLS state")
                }
            }
        } finally {
            plainIn.flip()
        }
    }

    /** Appends network bytes to [packetsIn]; false at end of stream. */
    private fun readPackets(): Boolean {
        packetsIn.compact()
        try {
            if (!packetsIn.hasRemaining()) {
                packetsIn = grow(packetsIn, engine.session.packetBufferSize)
            }
            val count = networkIn.read(
                packetsIn.array(),
                packetsIn.arrayOffset() + packetsIn.position(),
                packetsIn.remaining(),
            )
            if (count < 0) return false
            packetsIn.position(packetsIn.position() + count)
            return true
        } finally {
            packetsIn.flip()
        }
    }

    private fun wrap(source: ByteBuffer) {
        while (true) {
            packetsOut.clear()
            val result = engine.wrap(source, packetsOut)
            when (result.status) {
                SSLEngineResult.Status.OK, SSLEngineResult.Status.CLOSED -> {
                    packetsOut.flip()
                    networkOut.write(packetsOut.array(), 0, packetsOut.limit())
                    networkOut.flush()
                    if (result.handshakeStatus == SSLEngineResult.HandshakeStatus.NEED_TASK) runTasks()
                    return
                }
                SSLEngineResult.Status.BUFFER_OVERFLOW -> packetsOut =
                    ByteBuffer.allocate(maxOf(packetsOut.capacity() * 2, engine.session.packetBufferSize))
                SSLEngineResult.Status.BUFFER_UNDERFLOW, null -> throw SSLException("Cannot encrypt")
            }
        }
    }

    private fun closeOutbound() {
        synchronized(writeLock) {
            if (engine.isOutboundDone) return
            engine.closeOutbound()
            runCatching { wrap(EMPTY) }
        }
    }

    private fun runTasks() {
        while (true) {
            val task = engine.delegatedTask ?: return
            task.run()
        }
    }

    /** A larger buffer holding the same unread bytes, left ready for writing. */
    private fun grow(buffer: ByteBuffer, atLeast: Int): ByteBuffer {
        val grown = ByteBuffer.allocate(maxOf(buffer.capacity() * 2, atLeast))
        buffer.flip()
        grown.put(buffer)
        return grown
    }

    private companion object {
        val EMPTY: ByteBuffer = ByteBuffer.allocate(0)
    }
}
