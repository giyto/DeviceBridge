package ru.hznik.devicebridge.benchmark

import android.os.Debug
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


    private fun AtomicLong.updateMax(candidate: Long) {
        updateAndGet { current -> max(current, candidate) }
    }

    private data class AndroidMemorySnapshot(
        val totalPssKb: Long,
        val javaHeapBytes: Long,
    )

    private companion object {
        const val SAMPLE_INTERVAL_MILLIS = 250L
        const val BASELINE_WAIT_MILLIS = 1_000L
        const val RETAINED_WAIT_MILLIS = 30_000L
    }
}

data class StreamingMetricRecord(
    val operation: String,
    val run: Int,
    val baselinePssKb: Long,
    val peakPssKb: Long,
    val retainedPssKb: Long,
    val javaHeapPeakBytes: Long,
)
