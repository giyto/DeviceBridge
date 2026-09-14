package ru.hznik.devicebridge.diagnostics

import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticsEntryPointTest {

    @Test
    fun debugDiagnosticsActivityIsAvailable() {
        val activityExists = runCatching {
            Class.forName(
                "ru.hznik.devicebridge.diagnostics.DiagnosticsActivity",
                false,
                javaClass.classLoader,
            )
        }.isSuccess

        assertTrue("Debug diagnostics activity must be available", activityExists)
    }
}
