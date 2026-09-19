package ru.hznik.devicebridge.domain.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidDeviceNameTest {
    @Test
    fun combinesManufacturerAndModelWithoutDuplicatingManufacturer() {
        assertEquals("Google Pixel 8", androidDeviceName(" Google ", " Pixel 8 "))
        assertEquals("SAMSUNG SM-S918B", androidDeviceName("samsung", "SAMSUNG SM-S918B"))
    }

    @Test
    fun rejectsControlOnlyValuesAndFallsBackToGenericName() {
        assertEquals(
            SettingsDefaults.DEFAULT_DEVICE_NAME,
            androidDeviceName("bad\n", "\t"),
        )
    }

    @Test
    fun keepsTheResultWithinTheSettingsCodePointLimit() {
        val value = androidDeviceName("Manufacturer", "😀".repeat(50))

        assertTrue(value.codePointCount(0, value.length) <= 40)
        assertTrue(value.none(Char::isISOControl))
    }
}
