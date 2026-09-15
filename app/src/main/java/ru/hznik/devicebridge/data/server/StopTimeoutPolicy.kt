package ru.hznik.devicebridge.data.server

data class StopTimeoutPolicy(
    val timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
) {
    init {
        require(timeoutMillis > 0) { "Stop timeout must be positive" }
    }

    private companion object {
        const val DEFAULT_TIMEOUT_MILLIS = 3_000L
    }
}
