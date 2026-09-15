package ru.hznik.devicebridge.data.network

import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidLanAdapterContractTest {

    @Test
    fun adapterUsesLinkPropertiesFirstAndNetworkInterfacesAsFallback() {
        val source = Files.readString(
            Path.of(
                "src/main/java/ru/hznik/devicebridge/data/network/" +
                    "AndroidLanNetworkSnapshotProvider.kt",
            ),
        )

        assertTrue(source.contains("activeNetwork"))
        assertTrue(source.contains("getNetworkCapabilities"))
        assertTrue(source.contains("getLinkProperties"))
        assertTrue(source.contains("NetworkInterface.getNetworkInterfaces"))
    }
}
