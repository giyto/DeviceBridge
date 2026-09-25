package ru.hznik.devicebridge.data.file

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidFileDestinationGatewayTest {

    @Test
    fun approveCreatesScopedLeaseAndReleaseIsIdempotent() {
        val permissions = FakeTreePermissions()
        val gateway = AndroidFileDestinationGateway(permissions)

        val result = gateway.approve(
            uri = "content://provider/tree/folder",
            grantFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION,
        )
        val lease = (result as DestinationApproval.Approved).lease

        assertTrue(lease.isAvailable())
        lease.release()
        lease.release()
        assertEquals(listOf("content://provider/tree/folder"), permissions.released)
    }

    @Test
    fun cancelAndRevokedFolderDoNotCreateHiddenLease() {
        val permissions = FakeTreePermissions(canAcquire = false)
        val gateway = AndroidFileDestinationGateway(permissions)

        assertEquals(DestinationApproval.Cancelled, gateway.approve(null, 0))
        assertEquals(
            DestinationApproval.Unavailable,
            gateway.approve(
                "content://provider/tree/revoked",
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            ),
        )
        assertTrue(permissions.released.isEmpty())
        assertFalse(permissions.available)
    }

    private class FakeTreePermissions(
        private val canAcquire: Boolean = true,
    ) : DocumentTreePermissionGateway {
        val released = mutableListOf<String>()
        var available = canAcquire

        override fun acquire(uri: String, grantFlags: Int): Boolean = canAcquire

        override fun isAvailable(uri: String): Boolean = available

        override fun release(uri: String, grantFlags: Int) {
            released += uri
            available = false
        }
    }
}
