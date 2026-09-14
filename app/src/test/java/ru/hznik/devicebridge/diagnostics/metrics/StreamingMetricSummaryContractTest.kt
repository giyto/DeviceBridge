package ru.hznik.devicebridge.diagnostics.metrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamingMetricSummaryContractTest {

    @Test
    fun medianAndMachineReadableLineAreStable() {
        val result = runCatching {
            val recordClass = Class.forName(
                "ru.hznik.devicebridge.diagnostics.metrics.StreamingMetricRecord",
                false,
                javaClass.classLoader,
            )
            val constructor = recordClass.getConstructor(
                String::class.java,
                Int::class.javaPrimitiveType,
                Long::class.javaPrimitiveType,
                Long::class.javaPrimitiveType,
                Long::class.javaPrimitiveType,
                Long::class.javaPrimitiveType,
            )
            val records = listOf(
                constructor.newInstance("upload", 1, 10L, 80L, 20L, 50L),
                constructor.newInstance("upload", 2, 12L, 70L, 18L, 60L),
                constructor.newInstance("upload", 3, 11L, 90L, 19L, 40L),
            )
            val functions = Class.forName(
                "ru.hznik.devicebridge.diagnostics.metrics.StreamingMetricSummaryKt",
                false,
                javaClass.classLoader,
            )
            val median = requireNotNull(
                functions.getMethod("medianMetricRecords", List::class.java)
                    .invoke(null, records),
            )

            assertEquals(11L, median.readLong("getBaselinePssKb"))
            assertEquals(80L, median.readLong("getPeakPssKb"))
            assertEquals(19L, median.readLong("getRetainedPssKb"))
            assertEquals(50L, median.readLong("getJavaHeapPeakBytes"))

            val line = functions.getMethod(
                "toMachineReadableLine",
                median.javaClass,
            ).invoke(null, median) as String
            assertTrue(line.startsWith("DEVICEBRIDGE_STREAM_METRIC "))
            assertTrue(line.contains("\"operation\":\"upload\""))
            assertTrue(line.contains("\"runs\":3"))
            assertTrue(line.contains("\"peakPssKb\":80"))
        }

        assertTrue(
            result.exceptionOrNull()?.stackTraceToString() ?: "Metric summary failed",
            result.isSuccess,
        )
    }

    private fun Any.readLong(getter: String): Long {
        return javaClass.getMethod(getter).invoke(this) as Long
    }
}
