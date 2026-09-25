package ru.hznik.devicebridge.domain.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NetworkNameTest {

    @Test
    fun validNamesAreTrimmedAndLowerCased() {
        assertEquals("nikita", NetworkName.parse("  Nikita ")?.value)
        assertEquals("my-phone-2", NetworkName.parse("My-Phone-2")?.value)
        assertEquals("a", NetworkName.parse("a")?.value)
        assertEquals("x".repeat(40), NetworkName.parse("x".repeat(40))?.value)
        assertEquals("devicebridge", NetworkName.DEFAULT.value)
    }

    @Test
    fun namesThatBreakTheRulesAreRejected() {
        listOf(
            "",
            " ",
            "никита",
            "my phone",
            "-phone",
            "phone-",
            "phone.local",
            "phone_1",
            "x".repeat(41),
        ).forEach { input -> assertNull(input, NetworkName.parse(input)) }
    }
}
