package ru.hznik.devicebridge.data.file

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidFileSourcePickerGatewayInstrumentedTest {

    @Test
    fun openMultipleDocumentsContractRequestsOpenableMultipleSelection() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val intent = AndroidFileSourcePickerGateway.contract()
            .createIntent(context, arrayOf("*/*"))

        assertEquals(Intent.ACTION_OPEN_DOCUMENT, intent.action)
        assertTrue(intent.getBooleanExtra(Intent.EXTRA_ALLOW_MULTIPLE, false))
        assertEquals("*/*", intent.type)
    }

    @Test
    fun fakeMetadataContractKeepsMultipleResultsAndProviderFailure() {
        val metadata = mapOf(
            "content://fixture/one" to AndroidDocumentMetadata("one.txt", 1, "text/plain"),
            "content://fixture/two" to AndroidDocumentMetadata("two.bin", 2, null),
        )
        val gateway = AndroidFileSourcePickerGateway { uri -> metadata[uri] }

        val result = gateway.inspect(
            listOf("content://fixture/one", "content://fixture/two", "content://fixture/missing"),
        )

        assertEquals(listOf("one.txt", "two.bin"), result.mapNotNull { it.source?.displayName })
        assertEquals(
            AndroidFileSourceError.UNAVAILABLE_PROVIDER,
            result.last().error,
        )
    }

    @Test
    fun applicationDoesNotRequestBroadStoragePermissions() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val permissions = context.packageManager
            .getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
            .requestedPermissions
            .orEmpty()
            .toSet()

        assertFalse(Manifest.permission.READ_EXTERNAL_STORAGE in permissions)
        assertFalse(Manifest.permission.WRITE_EXTERNAL_STORAGE in permissions)
        assertFalse(Manifest.permission.MANAGE_EXTERNAL_STORAGE in permissions)
    }
}
