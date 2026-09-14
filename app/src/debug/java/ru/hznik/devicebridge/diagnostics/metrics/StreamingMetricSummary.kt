package ru.hznik.devicebridge.diagnostics.metrics

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class StreamingMetricRecord(
    val operation: String,
    val run: Int,
    val baselinePssKb: Long,
    val peakPssKb: Long,
    val retainedPssKb: Long,
    val javaHeapPeakBytes: Long,
)

@Serializable
data class StreamingMetricSummary(
    val operation: String,
    val runs: Int,
    val baselinePssKb: Long,
    val peakPssKb: Long,
    val retainedPssKb: Long,
    val javaHeapPeakBytes: Long,
)

fun medianMetricRecords(
    records: List<StreamingMetricRecord>,
): StreamingMetricSummary {
    require(records.isNotEmpty()) { "At least one metric record is required" }
    val operation = records.first().operation
    require(records.all { it.operation == operation }) {
        "All records must describe the same operation"
    }
    return StreamingMetricSummary(
        operation = operation,
        runs = records.size,
        baselinePssKb = records.map { it.baselinePssKb }.median(),
        peakPssKb = records.map { it.peakPssKb }.median(),
        retainedPssKb = records.map { it.retainedPssKb }.median(),
        javaHeapPeakBytes = records.map { it.javaHeapPeakBytes }.median(),
    )
}

fun StreamingMetricSummary.toMachineReadableLine(): String {
    return METRIC_PREFIX + Json.encodeToString(this)
}

private fun List<Long>.median(): Long {
    val sorted = sorted()
    val middle = sorted.size / 2
    return if (sorted.size % 2 == 1) {
        sorted[middle]
    } else {
        (sorted[middle - 1] + sorted[middle]) / 2
    }
}

private const val METRIC_PREFIX = "DEVICEBRIDGE_STREAM_METRIC "
