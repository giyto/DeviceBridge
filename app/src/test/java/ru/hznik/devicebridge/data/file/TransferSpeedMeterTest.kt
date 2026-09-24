package ru.hznik.devicebridge.data.file

import org.junit.Assert.assertEquals
import org.junit.Test

class TransferSpeedMeterTest {

    @Test
    fun measuresBytesOfThisAttemptOverWholeWindows() {
        var now = 0L
        val meter = TransferSpeedMeter(nowMillis = { now }, windowMillis = 1_000)

        // A resumed attempt starts at 50 MB; those bytes are not part of its speed.
        assertEquals(0L, meter.update(50_000_000))
        now = 400
        assertEquals(0L, meter.update(51_000_000))
        now = 1_000
        assertEquals(4_000_000L, meter.update(54_000_000))
        now = 1_500
        assertEquals(4_000_000L, meter.update(55_000_000))
        now = 2_000
        assertEquals(2_000_000L, meter.update(56_000_000))
    }
}
