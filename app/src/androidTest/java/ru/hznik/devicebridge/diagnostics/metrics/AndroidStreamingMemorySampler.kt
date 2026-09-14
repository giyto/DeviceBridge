package ru.hznik.devicebridge.diagnostics.metrics

import android.os.Bundle
import android.os.Debug
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.max

class AndroidStreamingMemorySampler {

    suspend fun <T> measure(
        operation: String,
        run: Int,
        block: suspend () -> T,
    ): Pair<T, StreamingMetricRecord> = coroutineScope {
        stabilizeMemory()
        val baseline = capture()
        val peakPssKb = AtomicLong(baseline.totalPssKb)
        val peakJavaHeapBytes = AtomicLong(baseline.javaHeapBytes)
        val sampler = launch(Dispatchers.Default) {
            while (isActive) {
                val sample = capture()
                peakPssKb.updateMax(sample.totalPssKb)
                peakJavaHeapBytes.updateMax(sample.javaHeapBytes)
                delay(SAMPLE_INTERVAL_MILLIS)
            }
        }

        val result = try {
            block()
        } finally {
            sampler.cancelAndJoin()
        }

        stabilizeMemory(RETAINED_WAIT_MILLIS)
        val retained = capture()
        val record = StreamingMetricRecord(
            operation = operation,
            run = run,
            baselinePssKb = baseline.totalPssKb,
            peakPssKb = peakPssKb.get(),
            retainedPssKb = retained.totalPssKb,
            javaHeapPeakBytes = peakJavaHeapBytes.get(),
        )
        emitMetric(record)
        result to record
    }

    private suspend fun stabilizeMemory(waitMillis: Long = BASELINE_WAIT_MILLIS) {
        Runtime.getRuntime().gc()
        delay(waitMillis)
    }

    private fun capture(): AndroidMemorySnapshot {
        val memoryInfo = Debug.MemoryInfo()
        Debug.getMemoryInfo(memoryInfo)
        val runtime = Runtime.getRuntime()
        return AndroidMemorySnapshot(
            totalPssKb = memoryInfo.totalPss.toLong(),
            javaHeapBytes = runtime.totalMemory() - runtime.freeMemory(),
        )
    }

    private fun emitMetric(record: StreamingMetricRecord) {
        val line = medianMetricRecords(listOf(record)).toMachineReadableLine()
        Log.i(LOG_TAG, line)
        InstrumentationRegistry.getInstrumentation().sendStatus(
            STATUS_CODE,
            Bundle().apply {
                putString(STATUS_KEY, line)
            },
        )
    }

    private fun AtomicLong.updateMax(candidate: Long) {
        updateAndGet { current -> max(current, candidate) }
    }

    private data class AndroidMemorySnapshot(
        val totalPssKb: Long,
        val javaHeapBytes: Long,
    )

    private companion object {
        const val LOG_TAG = "DeviceBridgeSpike"
        const val STATUS_KEY = "deviceBridgeStreamingMetric"
        const val STATUS_CODE = 2
        const val SAMPLE_INTERVAL_MILLIS = 250L
        const val BASELINE_WAIT_MILLIS = 1_000L
        const val RETAINED_WAIT_MILLIS = 30_000L
    }
}
