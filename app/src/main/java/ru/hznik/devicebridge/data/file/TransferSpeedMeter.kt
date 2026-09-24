package ru.hznik.devicebridge.data.file

/**
 * Bytes per second of one transfer attempt, measured over windows of about [windowMillis].
 * Starts from the first reported total, so bytes kept by an earlier attempt do not count.
 */
class TransferSpeedMeter(
    private val nowMillis: () -> Long = { System.nanoTime() / 1_000_000 },
    private val windowMillis: Long = 1_000,
) {
    private var windowStartMillis = -1L
    private var windowStartBytes = 0L
    private var bytesPerSecond = 0L

    fun update(totalBytes: Long): Long {
        val now = nowMillis()
        if (windowStartMillis < 0) {
            windowStartMillis = now
            windowStartBytes = totalBytes
            return bytesPerSecond
        }
        val elapsed = now - windowStartMillis
        if (elapsed >= windowMillis) {
            bytesPerSecond = (totalBytes - windowStartBytes).coerceAtLeast(0) * 1_000 / elapsed
            windowStartMillis = now
            windowStartBytes = totalBytes
        }
        return bytesPerSecond
    }
}
