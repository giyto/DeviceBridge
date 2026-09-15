package ru.hznik.devicebridge.data.network

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidLanNetworkSnapshotProviderTest {

    @Test
    fun resolvedEndpointIsNeverWildcardOrLoopback() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val snapshot = AndroidLanNetworkSnapshotProvider(context).snapshot()
        val resolution = LanEndpointResolver().resolve(snapshot)

        if (resolution is LanEndpointResolution.Resolved) {
            assertFalse(resolution.candidate.host == "0.0.0.0")
            assertFalse(resolution.candidate.host.startsWith("127."))
            assertTrue(resolution.candidate.networkFingerprint.isNotBlank())
        }
    }
}
