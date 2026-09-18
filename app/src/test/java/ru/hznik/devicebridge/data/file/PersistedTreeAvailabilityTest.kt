package ru.hznik.devicebridge.data.file

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PersistedTreeAvailabilityTest {
    @Test
    fun requiresBothPersistedGrantAndReachableProvider() {
        assertTrue(persistedTreeIsAvailable(hasGrant = true) { true })
        assertFalse(persistedTreeIsAvailable(hasGrant = false) { true })
        assertFalse(persistedTreeIsAvailable(hasGrant = true) { false })
    }

    @Test
    fun providerFailureIsUnavailableInsteadOfCrashing() {
        assertFalse(
            persistedTreeIsAvailable(hasGrant = true) {
                error("provider unavailable")
            },
        )
    }
}
