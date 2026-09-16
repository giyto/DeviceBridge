package ru.hznik.devicebridge.web

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith

@LargeTest
@RunWith(AndroidJUnit4::class)
class ProductionFileTransferInstrumentedTest {

    @Test
    fun productionRoutesStreamBoundariesAndFiveHundredMiBInBothDirections() = runBlocking {
        ProductionFileTransferBenchmarkHarness().use { harness ->
            harness.verifyZeroByteBothDirections()
            harness.verifyOversizeOfferRejected()
            harness.verifyFiveHundredMiBBothDirections()
        }
    }
}
